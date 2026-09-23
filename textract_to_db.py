#!/usr/bin/env python3
"""
textract_to_db.py — one document in, one document_text row per page out.

    python textract_to_db.py --case-document-id 42 --s3 cases/POCVISA13117/merchant.pdf
    python textract_to_db.py --case-document-id 42 --file merchant.pdf --print

Textract is called per page, and only for the pages that need it. Pages with a
clean digital text layer are read from the layer itself and cost nothing.

Steps, top to bottom in this file:

  1. fetch + hash the document   -> content_hash (the cache key)
  2. cache check                 -> skip everything if already read by this reader
  3. split + classify each page  -> TEXTRACT or DIGITAL, by these rules in order:
        a. no text layer (or almost none)               -> TEXTRACT
        b. an embedded image covers > 10% of the page   -> TEXTRACT (text inside images
                                                            is not in the text layer)
        c. ruled lines form a table                     -> TEXTRACT (reliable cell reading)
        d. text layer looks broken (overlapping words,
           garbled characters)                          -> TEXTRACT
        e. otherwise                                    -> DIGITAL, read from the text layer
  4. Textract AnalyzeDocument    -> sync, one call per TEXTRACT page, a few in parallel,
                                    LAYOUT + TABLES + SIGNATURES (FORMS off: 4x the price,
                                    nothing gained for the text)
  5. render each page to text    -> both paths use the same conventions: reading order,
                                    lines on one baseline merged, blank line between blocks,
                                    [signature] and [image] markers
  6. read quality per page       -> DIGITAL_TEXT, or VISION_HIGH / VISION_LOW from Textract's
                                    word confidences — never from a model
  7. mask PCI / PII              -> Amazon Bedrock Guardrails hook (pass-through until wired)
  8. write to document_text      -> INSERT one row per page (UPDATE only when superseding an
                                    older reader_version)

Environment:
  AWS credentials as usual (env / profile / role)
  DATABASE_URL     e.g. postgresql://user:pass@host:5432/visa131
  AWS_REGION       default eu-west-1
  TEXTRACT_BUCKET  S3 bucket for --s3 reads
"""

from __future__ import annotations

import argparse
import hashlib
import io
import logging
import os
import re
import sys
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Sequence, Tuple

import boto3
import pdfplumber
import psycopg2
from botocore.config import Config as BotoConfig
from pypdf import PdfReader, PdfWriter

log = logging.getLogger("textract_to_db")

# =============================================================================
# 0. Settings — change the rendering rules → bump READER_VERSION
# =============================================================================
READER_VERSION = "hybrid-1.0.0"          # text layer + per-page Textract
FEATURE_TYPES = ["LAYOUT", "TABLES", "SIGNATURES"]

# page classification (step 3)
MIN_TEXT_CHARS = 20          # fewer characters than this → no usable text layer
IMAGE_SHARE_FOR_TEXTRACT = 0.10   # embedded images covering more than this → Textract
SUSPECT_OVERLAP_SHARE = 0.02      # share of words overlapping another word → layout suspect
SUSPECT_ALNUM_SHARE = 0.50        # below this share of alphanumeric characters → garbled

# per-page Textract calls (step 4)
MAX_CONCURRENCY = 4
SYNC_MAX_BYTES = 9_500_000   # sync AnalyzeDocument limit is 10 MB; rasterise above this
RASTER_DPI = 150

BASELINE_OVERLAP = 0.4     # lines whose heights overlap this much sit on one baseline
MIN_FIGURE_AREA = 0.03     # figures smaller than 3% of the page are ignored (logos, icons)
HIGH_QUALITY_MEAN = 95.0   # mean word confidence at/above this → VISION_HIGH
CELL_SEPARATOR = " | "
WORD_SEPARATOR = " "         # between lines merged onto one baseline
SIGNATURE_MARKER = "[signature]"
FIGURE_MARKER = "[image]"

Block = Dict[str, Any]


@dataclass(frozen=True)
class PageRead:
    page: int
    text: str
    read_quality: str
    reader_version: str = READER_VERSION


@dataclass(frozen=True)
class PagePlan:
    """What step 3 decided for one page, and why."""
    page: int
    route: str          # "TEXTRACT" | "DIGITAL"
    reason: str


# =============================================================================
# 1. Content hash
# =============================================================================
def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# =============================================================================
# 2 + 6. The database side
# =============================================================================
SQL_CACHED = """
    SELECT DISTINCT ON (page) page, text, read_quality, reader_version
      FROM document_text
     WHERE content_hash = %s
     ORDER BY page, id DESC
"""

SQL_INSERT = """
    INSERT INTO document_text
           (case_document_id, content_hash, page, text, read_quality, reader_version)
    VALUES (%s, %s, %s, %s, %s, %s)
    ON CONFLICT (case_document_id, page) DO UPDATE
       SET text           = EXCLUDED.text,
           read_quality   = EXCLUDED.read_quality,
           content_hash   = EXCLUDED.content_hash,
           reader_version = EXCLUDED.reader_version
     WHERE document_text.reader_version IS DISTINCT FROM EXCLUDED.reader_version
"""


def cached_pages(conn, content_hash: str) -> List[PageRead]:
    with conn.cursor() as cur:
        cur.execute(SQL_CACHED, (content_hash,))
        rows = cur.fetchall()
    return [PageRead(page=r[0], text=r[1], read_quality=r[2], reader_version=r[3]) for r in rows]


def store_pages(conn, case_document_id: int, content_hash: str, pages: Sequence[PageRead]) -> None:
    with conn.cursor() as cur:
        for p in pages:
            cur.execute(SQL_INSERT, (case_document_id, content_hash, p.page,
                                     p.text, p.read_quality, p.reader_version))
    conn.commit()


# =============================================================================
# 3. Split and classify each page — decide who reads it
# =============================================================================
def is_pdf(data: bytes) -> bool:
    return data[:5] == b"%PDF-"


def classify_page(page: "pdfplumber.page.Page") -> PagePlan:
    """The five rules, in order. First match wins."""
    n = page.page_number
    page_area = float(page.width * page.height) or 1.0

    # a. no text layer
    chars = page.chars
    if len(chars) < MIN_TEXT_CHARS:
        return PagePlan(n, "TEXTRACT", "no text layer")

    # b. a large embedded image — its text is not in the layer
    image_area = 0.0
    for im in page.images:
        image_area += max(0.0, im["x1"] - im["x0"]) * max(0.0, im["bottom"] - im["top"])
    if image_area / page_area > IMAGE_SHARE_FOR_TEXTRACT:
        return PagePlan(n, "TEXTRACT", f"image covers {image_area / page_area:.0%} of page")

    # c. a ruled table
    tables = page.find_tables()
    if any(len(t.rows) >= 2 and len(t.rows[0].cells) >= 2 for t in tables):
        return PagePlan(n, "TEXTRACT", f"{len(tables)} ruled table(s)")

    # d. a text layer that cannot be trusted
    text = "".join(c["text"] for c in chars)
    alnum = sum(ch.isalnum() for ch in text) / max(1, sum(not ch.isspace() for ch in text))
    if alnum < SUSPECT_ALNUM_SHARE:
        return PagePlan(n, "TEXTRACT", f"garbled text layer ({alnum:.0%} alphanumeric)")
    words = page.extract_words()
    overlapping = 0
    by_top = sorted(words, key=lambda w: (w["top"], w["x0"]))
    for i, w in enumerate(by_top):
        for other in by_top[i + 1:i + 6]:
            if other["top"] > w["bottom"]:
                break
            dx = min(w["x1"], other["x1"]) - max(w["x0"], other["x0"])
            dy = min(w["bottom"], other["bottom"]) - max(w["top"], other["top"])
            if dx > 0 and dy > 0 and dx * dy > 0.3 * (w["x1"] - w["x0"]) * (w["bottom"] - w["top"]):
                overlapping += 1
                break
    if words and overlapping / len(words) > SUSPECT_OVERLAP_SHARE:
        return PagePlan(n, "TEXTRACT", f"layout suspect ({overlapping} overlapping words)")

    # e. clean digital text
    return PagePlan(n, "DIGITAL", "clean text layer")


def single_page_pdf(reader: PdfReader, index: int) -> bytes:
    writer = PdfWriter()
    writer.add_page(reader.pages[index])
    buf = io.BytesIO()
    writer.write(buf)
    return buf.getvalue()


def rasterise_page(page: "pdfplumber.page.Page") -> bytes:
    buf = io.BytesIO()
    page.to_image(resolution=RASTER_DPI).original.save(buf, format="PNG")
    return buf.getvalue()


# =============================================================================
# 4. Textract — sync AnalyzeDocument, one call per page that needs it
# =============================================================================
def textract_client(region: str):
    return boto3.client(
        "textract",
        region_name=region,
        config=BotoConfig(retries={"max_attempts": 8, "mode": "adaptive"},   # rides out throttling
                          max_pool_connections=MAX_CONCURRENCY),
    )


def analyze_page_bytes(client, page_bytes: bytes) -> List[Block]:
    response = client.analyze_document(
        Document={"Bytes": page_bytes},
        FeatureTypes=FEATURE_TYPES,
    )
    return response["Blocks"]


def analyze_pages(client, jobs: Sequence[Tuple[int, bytes]]) -> Dict[int, List[Block]]:
    """``jobs`` is (page_number, single-page bytes). A few run at once; boto's
    adaptive retry absorbs the account's transactions-per-second limit."""
    results: Dict[int, List[Block]] = {}
    if not jobs:
        return results

    def one(job: Tuple[int, bytes]) -> Tuple[int, List[Block]]:
        number, data = job
        log.info("textract page %d (%d bytes)", number, len(data))
        return number, analyze_page_bytes(client, data)

    with ThreadPoolExecutor(max_workers=MAX_CONCURRENCY) as pool:
        for number, blocks in pool.map(one, jobs):
            results[number] = blocks
    return results


# =============================================================================
# 5a. Rendering one page of Textract blocks to text
# =============================================================================
def _box(b: Block) -> Dict[str, float]:
    return b["Geometry"]["BoundingBox"]


def _area(b: Block) -> float:
    return _box(b)["Width"] * _box(b)["Height"]


def _overlap_area(a: Block, b: Block) -> float:
    ba, bb = _box(a), _box(b)
    dx = max(0.0, min(ba["Left"] + ba["Width"], bb["Left"] + bb["Width"]) - max(ba["Left"], bb["Left"]))
    dy = max(0.0, min(ba["Top"] + ba["Height"], bb["Top"] + bb["Height"]) - max(ba["Top"], bb["Top"]))
    return dx * dy


def _same_baseline(a: Block, b: Block) -> bool:
    ba, bb = _box(a), _box(b)
    top = max(ba["Top"], bb["Top"])
    bottom = min(ba["Top"] + ba["Height"], bb["Top"] + bb["Height"])
    shortest = min(ba["Height"], bb["Height"]) or 1e-9
    return (bottom - top) > BASELINE_OVERLAP * shortest


def _reading_key(b: Block):
    return (round(_box(b)["Top"], 4), round(_box(b)["Left"], 4))


class Page:
    """The blocks of one page, indexed for lookups."""

    def __init__(self, blocks: Sequence[Block], page_number: int):
        self.by_id = {b["Id"]: b for b in blocks}
        self.blocks = [b for b in blocks if b.get("Page", 1) == page_number]
        self.lines = [b for b in self.blocks if b["BlockType"] == "LINE"]
        self.words = [b for b in self.blocks if b["BlockType"] == "WORD"]
        self.tables = [b for b in self.blocks if b["BlockType"] == "TABLE"]
        self.signatures = [b for b in self.blocks if b["BlockType"] == "SIGNATURE"]
        self.layouts = [b for b in self.blocks if b["BlockType"].startswith("LAYOUT_")]

    def children(self, block: Block) -> List[Block]:
        for rel in block.get("Relationships", []):
            if rel["Type"] == "CHILD":
                return [self.by_id[i] for i in rel["Ids"] if i in self.by_id]
        return []


def merge_baselines(lines: Sequence[Block]) -> List[str]:
    """Lines on one baseline become one output line, left to right."""
    rows: List[List[Block]] = []
    for line in sorted(lines, key=_reading_key):
        if rows and _same_baseline(rows[-1][0], line):
            rows[-1].append(line)
        else:
            rows.append([line])
    return [WORD_SEPARATOR.join(l["Text"] for l in sorted(r, key=lambda l: _box(l)["Left"]))
            for r in rows]


def render_table(page: Page, table: Block) -> List[str]:
    cells = sorted((c for c in page.children(table) if c["BlockType"] == "CELL"),
                   key=lambda c: (c["RowIndex"], c["ColumnIndex"]))
    if not cells:
        return merge_baselines(page.children(table))

    rendered = {(c["RowIndex"], c["ColumnIndex"]): merge_baselines(page.children(c)) for c in cells}

    if all(len(v) <= 1 for v in rendered.values()):
        # a real grid: one row per line, columns separated
        n_rows = max(r for r, _ in rendered)
        n_cols = max(c for _, c in rendered)
        return [CELL_SEPARATOR.join(" ".join(rendered.get((r, c), [])) for c in range(1, n_cols + 1)).rstrip()
                for r in range(1, n_rows + 1)]

    # a ruled panel (address blocks, field groups): keep each cell's lines, cells in row order
    return ["\n".join(rendered[(c["RowIndex"], c["ColumnIndex"])])
            for c in cells if rendered[(c["RowIndex"], c["ColumnIndex"])]]


def signature_anchors(page: Page) -> Dict[str, str]:
    """Each signature is attached to the nearest text line above it."""
    anchors = {}
    for sig in page.signatures:
        above = [l for l in page.lines
                 if _box(l)["Top"] + _box(l)["Height"] <= _box(sig)["Top"] + 0.002]
        if above:
            anchors[sig["Id"]] = max(above, key=lambda l: _box(l)["Top"])["Id"]
    return anchors


def insert_signatures(page: Page, out_lines: List[str], line_blocks: Sequence[Block],
                      anchors: Dict[str, str], placed: set) -> List[str]:
    ids_here = {l["Id"] for l in line_blocks}
    for sig in page.signatures:
        anchor = anchors.get(sig["Id"])
        if sig["Id"] in placed or anchor not in ids_here:
            continue
        anchor_text = page.by_id[anchor]["Text"]
        for i, rendered in enumerate(out_lines):
            if anchor_text in rendered:
                out_lines.insert(i + 1, SIGNATURE_MARKER)
                placed.add(sig["Id"])
                break
    return out_lines


def render_chunks(page: Page) -> List[str]:
    chunks: List[str] = []
    consumed: set = set()
    placed: set = set()
    anchors = signature_anchors(page)

    for layout in page.layouts:                       # Textract returns these in reading order
        kids = [k for k in page.children(layout) if k["BlockType"] == "LINE"]

        if layout["BlockType"] == "LAYOUT_TABLE" and page.tables:
            table = max(page.tables, key=lambda t: _overlap_area(t, layout))
            for chunk in render_table(page, table):
                chunks.append("\n".join(insert_signatures(page, chunk.split("\n"), kids, anchors, placed)))
            consumed.update(k["Id"] for k in kids)
            for cell in page.children(table):
                consumed.update(c["Id"] for c in page.children(cell))
            continue

        if layout["BlockType"] == "LAYOUT_FIGURE":
            inner = merge_baselines(kids)             # text printed inside the picture
            if _area(layout) >= MIN_FIGURE_AREA or inner:
                chunks.append("\n".join([FIGURE_MARKER] + inner))
            consumed.update(k["Id"] for k in kids)
            continue

        lines = insert_signatures(page, merge_baselines(kids), kids, anchors, placed)
        if lines:
            chunks.append("\n".join(lines))
        consumed.update(k["Id"] for k in kids)

    leftovers = [l for l in page.lines if l["Id"] not in consumed]   # nothing is dropped
    if leftovers:
        chunks.append("\n".join(insert_signatures(page, merge_baselines(leftovers), leftovers, anchors, placed)))
    for sig in page.signatures:
        if sig["Id"] not in placed:
            chunks.append(SIGNATURE_MARKER)
    return chunks


# --- normalising -------------------------------------------------------------
def normalise(chunks: Sequence[str]) -> str:
    text = "\n\n".join(c for c in chunks if c.strip())
    text = unicodedata.normalize("NFC", text).replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = "\n".join(l.rstrip() for l in text.split("\n"))
    return re.sub(r"\n{3,}", "\n\n", text).strip()


# =============================================================================
# 5b. Rendering one page from its text layer — same conventions as 5a
# =============================================================================
def render_digital_page(page: "pdfplumber.page.Page") -> str:
    words = page.extract_words(keep_blank_chars=False, use_text_flow=False)
    if not words:
        return ""
    words.sort(key=lambda w: (round(w["top"], 1), w["x0"]))

    # group words onto baselines (same test as the Textract path)
    rows: List[List[Dict[str, Any]]] = []
    for w in words:
        if rows:
            first = rows[-1][0]
            top, bottom = max(first["top"], w["top"]), min(first["bottom"], w["bottom"])
            shortest = min(first["bottom"] - first["top"], w["bottom"] - w["top"]) or 1e-9
            if (bottom - top) > BASELINE_OVERLAP * shortest:
                rows[-1].append(w)
                continue
        rows.append([w])

    # join each row left to right
    lines: List[Tuple[float, float, str]] = []
    for row in rows:
        row.sort(key=lambda w: w["x0"])
        lines.append((row[0]["top"], row[0]["bottom"], WORD_SEPARATOR.join(w["text"] for w in row)))

    # figures that matter get a marker at their position
    page_area = float(page.width * page.height) or 1.0
    for im in page.images:
        area = max(0.0, im["x1"] - im["x0"]) * max(0.0, im["bottom"] - im["top"])
        if area / page_area >= MIN_FIGURE_AREA:
            lines.append((im["top"], im["bottom"], FIGURE_MARKER))
    lines.sort(key=lambda l: l[0])

    # a vertical gap larger than a line becomes a block boundary (blank line)
    out: List[str] = []
    prev_bottom: Optional[float] = None
    for top, bottom, text in lines:
        if prev_bottom is not None and top - prev_bottom > 0.8 * (bottom - top):
            out.append("")
        out.append(text)
        prev_bottom = bottom
    return normalise(["\n".join(out)])


# =============================================================================
# 6. Read quality — from the engine, never from a model
# =============================================================================
def read_quality(page: Page) -> str:
    conf = [w["Confidence"] for w in page.words if "Confidence" in w]
    if not conf:
        return "VISION_LOW"
    return "VISION_HIGH" if sum(conf) / len(conf) >= HIGH_QUALITY_MEAN else "VISION_LOW"


def render_page(blocks: Sequence[Block], page_number: int) -> PageRead:
    page = Page(blocks, page_number)
    text = normalise(render_chunks(page))
    return PageRead(page=page_number, text=text, read_quality=read_quality(page))


def mask_sensitive_data(pages: Sequence[PageRead]) -> List[PageRead]:
    """Where the Amazon Bedrock Guardrails call goes (ApplyGuardrail with a
    sensitive-information policy for card numbers, names, addresses, emails,
    phones). Runs on the rendered text of every page, before anything is
    stored, so no PAN or PII ever reaches document_text.

    Until it is wired in, this is a pass-through. When it is, remember that the
    masked text is what quotes are checked against, so the same guardrail
    version must apply to the prompt's copy and the stored copy — and bump
    READER_VERSION when the guardrail configuration changes."""
    return list(pages)


def render_digital(page: "pdfplumber.page.Page") -> PageRead:
    return PageRead(page=page.page_number, text=render_digital_page(page), read_quality="DIGITAL_TEXT")


# =============================================================================
# The whole flow
# =============================================================================
def fetch(file_path: Optional[str], s3_bucket: Optional[str], s3_key: Optional[str]) -> bytes:
    if file_path:
        with open(file_path, "rb") as fh:
            return fh.read()
    if s3_bucket and s3_key:
        return boto3.client("s3").get_object(Bucket=s3_bucket, Key=s3_key)["Body"].read()
    raise ValueError("file_path or s3_bucket + s3_key is required")


def plan_and_read(client, document_bytes: bytes) -> Tuple[List[PagePlan], List[PageRead]]:
    """Steps 3–6 for one document: classify every page, send only the TEXTRACT
    pages, render both kinds with the same conventions."""
    # a single image (JPEG / PNG) is one page, and Textract's
    if not is_pdf(document_bytes):
        plan = [PagePlan(1, "TEXTRACT", "image file")]
        blocks = analyze_pages(client, [(1, document_bytes)])[1]
        return plan, [render_page(blocks, 1)]

    reader = PdfReader(io.BytesIO(document_bytes))
    plans: List[PagePlan] = []
    reads: Dict[int, PageRead] = {}
    jobs: List[Tuple[int, bytes]] = []

    with pdfplumber.open(io.BytesIO(document_bytes)) as pdf:
        for page in pdf.pages:
            plan = classify_page(page)
            plans.append(plan)
            log.info("page %d → %s (%s)", plan.page, plan.route, plan.reason)
            if plan.route == "DIGITAL":
                reads[plan.page] = render_digital(page)
            else:
                data = single_page_pdf(reader, plan.page - 1)
                if len(data) > SYNC_MAX_BYTES:
                    data = rasterise_page(page)
                jobs.append((plan.page, data))

        # Textract pages: blocks come back with Page=1 each, so render per call
        for number, blocks in analyze_pages(client, jobs).items():
            read = render_page(blocks, 1)
            reads[number] = PageRead(page=number, text=read.text, read_quality=read.read_quality)

    return plans, [reads[n] for n in sorted(reads)]


def read_document(conn, client, case_document_id: int, *,
                  file_path: Optional[str] = None,
                  s3_bucket: Optional[str] = None, s3_key: Optional[str] = None,
                  content_hash: Optional[str] = None) -> List[PageRead]:
    # 1. fetch + hash
    document_bytes = fetch(file_path, s3_bucket, s3_key)
    content_hash = content_hash or sha256(document_bytes)

    # 2. cache
    cached = cached_pages(conn, content_hash)
    if cached and all(p.reader_version == READER_VERSION for p in cached):
        log.info("reading %s from store (%d pages)", content_hash[:12], len(cached))
        store_pages(conn, case_document_id, content_hash, cached)     # bind to this attachment
        return sorted(cached, key=lambda p: p.page)

    # 3–6. classify, read, render
    plans, pages = plan_and_read(client, document_bytes)
    sent = sum(1 for p in plans if p.route == "TEXTRACT")
    log.info("%s: %d pages, %d sent to Textract, %d read from text layer",
             content_hash[:12], len(plans), sent, len(plans) - sent)

    # 7. mask PCI / PII before anything is stored (Bedrock Guardrails — pass-through until wired)
    pages = mask_sensitive_data(pages)

    # 8. store
    store_pages(conn, case_document_id, content_hash, pages)
    return pages


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case-document-id", type=int, required=True)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--file", help="local PDF or single image")
    src.add_argument("--s3", help="S3 key of a PDF or image in $TEXTRACT_BUCKET")
    ap.add_argument("--print", action="store_true", help="print each page's text after storing")
    args = ap.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    region = os.environ.get("AWS_REGION", "eu-west-1")
    conn = psycopg2.connect(os.environ["DATABASE_URL"])
    client = textract_client(region)

    pages = read_document(
        conn, client, args.case_document_id,
        file_path=args.file,
        s3_bucket=os.environ.get("TEXTRACT_BUCKET") if args.s3 else None,
        s3_key=args.s3,
    )
    if args.print:
        for p in pages:
            print(f"--- [page {p.page}] {p.read_quality} {p.reader_version} ---")
            print(p.text)
    return 0


if __name__ == "__main__":
    sys.exit(main())

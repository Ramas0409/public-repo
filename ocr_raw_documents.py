r"""Reads a folder of documents through Textract directly and keeps each document's raw OCR text, unmasked.

The raw counterpart of ``scripts/ocr_documents.py``: the same input folder, file selection and sides, but no service,
no masking, no guardrail and no database. Every page goes to Textract (``TextractEngine``), and each document is
written to ``ocr_raw_output/<path under ocr_input>.json`` as ``{ref, declaredSide, contentHash, readerVersion,
readable, pages: [{page, text, readQuality}]}``: the ref is the path under ``ocr_input``, the side the ``issuer`` or
``merchant`` folder the document is in, the hash the sha256 of its bytes. A document whose file is already there is
skipped, so a stopped run goes on where it stopped. Textract bills per page.

The output holds card numbers and personal data exactly as printed, and is never committed or shared. It imports
``app``, so it runs from the project root (the folder holding ``app/`` and ``scripts/``) as a module, with AWS
credentials and a region in the environment or in the project's ``.env``, which it loads (a variable already set
wins).

    python -m scripts.ocr_raw_documents                       # ocr_input -> ocr_raw_output
    python -m scripts.ocr_raw_documents --dry-run             # list what would be read
    python -m scripts.ocr_raw_documents --cases CASE1 CASE2   # only the files under these top-level folders
    python -m scripts.ocr_raw_documents --root "C:\some\folder" --out "C:\some\text"
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import List, Optional

from dotenv import load_dotenv

from app.services.extraction.model import UnopenableDocument, content_hash
from app.services.extraction.textract import TextractEngine, textract_available
from scripts.ocr_documents import INPUT_FOLDER, PROJECT_ROOT, documents, key_of, side_of

OUTPUT_FOLDER = PROJECT_ROOT / "ocr_raw_output"


def output_path(out: Path, relative: Path) -> Path:
    return out / relative.parent / (relative.name + ".json")


def reading(relative: Path, payload: bytes, engine) -> dict:
    """One document's raw reading: its ref, side, hash, reader version and pages."""
    pages = engine.read_pages(payload)
    return {"ref": key_of(relative), "declaredSide": side_of(relative), "contentHash": content_hash(payload),
            "readerVersion": engine.reader_version, "readable": any(page.text.strip() for page in pages),
            "pages": [{"page": page.number, "text": page.text, "readQuality": page.source_quality.value}
                      for page in pages]}


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Read every document through Textract and keep its raw text.")
    parser.add_argument("--root", default=str(INPUT_FOLDER), help=f"the documents folder (default {INPUT_FOLDER})")
    parser.add_argument("--out", default=str(OUTPUT_FOLDER), help=f"where the readings go (default {OUTPUT_FOLDER})")
    parser.add_argument("--cases", nargs="*", help="only the files under these top-level folders")
    parser.add_argument("--dry-run", action="store_true", help="list the documents and read nothing")
    arguments = parser.parse_args(argv)
    load_dotenv()

    root, out = Path(arguments.root), Path(arguments.out)
    if not root.is_dir():
        print(f"no folder {root}")
        return 2
    relatives = documents(root, arguments.cases)
    pending = [relative for relative in relatives if not output_path(out, relative).exists()]
    print(f"{len(relatives)} document(s) under {root}; {len(relatives) - len(pending)} already read, "
          f"{len(pending)} to read")
    if arguments.dry_run:
        for relative in pending:
            print(f"  {key_of(relative)}  side={side_of(relative)}")
        return 0
    if not (os.getenv("AWS_REGION") or os.getenv("AWS_DEFAULT_REGION")) or not textract_available():
        print("Textract cannot be called: set AWS_REGION and AWS_PROFILE (or AWS_ACCESS_KEY_ID and "
              "AWS_SECRET_ACCESS_KEY)")
        return 2

    engine = TextractEngine()
    failed = []
    for index, relative in enumerate(pending, start=1):
        started = time.time()
        try:
            document = reading(relative, (root / relative).read_bytes(), engine)
        except UnopenableDocument as exc:
            failed.append(key_of(relative))
            print(f"[{index}/{len(pending)}] {key_of(relative)}  UNREADABLE  {exc}")
            continue
        except Exception as exc:
            failed.append(key_of(relative))
            print(f"[{index}/{len(pending)}] {key_of(relative)}  FAILED  {type(exc).__name__}: {exc}")
            continue
        target = output_path(out, relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[{index}/{len(pending)}] {key_of(relative)}  {len(document['pages'])} page(s)  "
              f"side={document['declaredSide']}  {time.time() - started:.1f}s")
    print(f"done: {len(pending) - len(failed)} read, {len(failed)} failed")
    for key in failed:
        print("  failed: " + key)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

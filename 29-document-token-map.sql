-- Office DB step 29 (wdp_ai), POC db/pci/V2001 (decision 41). Written, not run: the token map of a case document's
-- current reading, one row per document, as UTF-8 JSON bytes; PII only, never card data. Written by the llm service with
-- the pages in one transaction and deleted with them; read by the llm service alone. MASKING_PII_ENABLED=false writes
-- no row. Needs step 28. Safe to run again.

BEGIN;

CREATE TABLE IF NOT EXISTS wdp_ai.document_token_map (
    case_document_id bigint PRIMARY KEY REFERENCES wdp_ai.case_document (id) ON DELETE CASCADE,
    map bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

COMMIT;

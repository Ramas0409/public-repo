-- Office DB step 37 (wdp_ai), POC db/V64__merchant_documents.sql (CL-3099). Written, not run: the AI decision
-- summary's merchant-document vocabulary. Run this, then the re-seed, step 37b (37b-register-seed.sql, the POC's
-- db/V2__seed.sql as of the stage), as the role that owns wdp_ai.reason_code; then grant the orchestrator's user
-- SELECT on wdp_ai.merchant_document_type. Safe to run again. No case data moves.

BEGIN;

ALTER TABLE wdp_ai.reason_code ADD COLUMN IF NOT EXISTS plain_name varchar(128);
ALTER TABLE wdp_ai.reason_code ADD COLUMN IF NOT EXISTS first_asks text[] NOT NULL DEFAULT '{}';
ALTER TABLE wdp_ai.requirement ADD COLUMN IF NOT EXISTS merchant_documents text[] NOT NULL DEFAULT '{}';

CREATE TABLE IF NOT EXISTS wdp_ai.merchant_document_type (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reason_code_id  bigint       NOT NULL REFERENCES wdp_ai.reason_code(id),
    key             varchar(48)  NOT NULL,
    label           varchar(128) NOT NULL,
    ask_text        text         NOT NULL,
    why_text        text         NOT NULL,
    display_order   int          NOT NULL,
    UNIQUE (reason_code_id, key)
);

COMMIT;

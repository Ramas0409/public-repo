-- Office DB step 28 (wdp_ai), POC db/V47 (ruling 41). Written, not run: a fact is replayed only while the document reading it came from is
-- current. extracted_fact records the reader of that reading; a document read again by another reader asks its
-- facts again, written as new rows beside the old ones, so the reader is part of the replay key.
-- The rows before V47 carry no reader: none of them replays, and every case's facts are asked again once.

BEGIN;

ALTER TABLE wdp_ai.extracted_fact ADD COLUMN reader_version varchar(32);

ALTER TABLE wdp_ai.extracted_fact DROP CONSTRAINT extracted_fact_replay_unique;
ALTER TABLE wdp_ai.extracted_fact ADD CONSTRAINT extracted_fact_replay_unique
    UNIQUE (dispute_case_id, case_document_id, fact_key_id, contract_version, question_version,
            extraction_prompt_version, retry_prompt_version, reader_version);

COMMIT;

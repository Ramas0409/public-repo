-- Office step 5: extracted_fact gains the replay key the POC has: one row per case, document, question and
-- set of versions. The table's columns are unchanged. The first statement lists any duplicates; the ALTER fails
-- while one exists.

SELECT dispute_case_id, case_document_id, fact_key_id, contract_version, question_version,
       extraction_prompt_version, retry_prompt_version, count(*) AS copies
  FROM wdp_ai.extracted_fact
 GROUP BY 1, 2, 3, 4, 5, 6, 7
HAVING count(*) > 1;

BEGIN;

ALTER TABLE wdp_ai.extracted_fact ADD CONSTRAINT extracted_fact_replay_unique
    UNIQUE (dispute_case_id, case_document_id, fact_key_id, contract_version,
            question_version, extraction_prompt_version, retry_prompt_version);

COMMIT;

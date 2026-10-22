-- Run in DEV, after the cases are in wdp."case" and wdp.action (prod_case_action_to_dev.sql) and their documents are
-- in dev's document service. Queues one RUN row per case in wdp.ai_response_events, the row the scheduler claims: a
-- merchant's response document uploaded (DOCUMENT_UPLOADED + RESPDOC) on the case's action '01', in the mode given.
--   REVIEW_MODE: the case is run, its questionnaire is saved through the questionnaire service and the network letter
--                uploaded to the document service, so operations can open and validate both in the portal.
--   SILENT_MODE: the case is run, nothing is saved through the questionnaire service, the run row keeps everything.
--   QA_MODE (non-production only): run in REVIEW_MODE (so saved and visible as above), then compared with
--                                  operations' questionnaire in wdp_ai.qa_ops_questionnaire
--                                  (prod_ops_questionnaires_to_dev.sql) into wdp_ai.contest_comparison.
-- A case that already has a PENDING or PROCESSING run row is left alone. The columns are office DB step 30's
-- (provisional): if dev's table is the estate's own, map them to its names.
WITH wanted(i_case, mode) AS (
    VALUES ('CASE_NUMBER_1', 'REVIEW_MODE'),
           ('CASE_NUMBER_2', 'QA_MODE')
)
INSERT INTO wdp.ai_response_events (i_case, i_action_seq, c_acq_platform, c_reason, c_case_ntwk, c_event_type,
                                    c_doc_type, mode)
SELECT c.i_case, a.i_action_seq, c.c_acq_platform, a.c_reason, c.c_case_ntwk, 'DOCUMENT_UPLOADED', 'RESPDOC', w.mode
FROM wanted w
JOIN wdp."case" c ON c.i_case = w.i_case
JOIN wdp.action a ON a.i_case = c.i_case AND a.i_action_seq = '01'
WHERE NOT EXISTS (SELECT 1 FROM wdp.ai_response_events e
                  WHERE e.i_case = c.i_case AND e.i_action_seq = a.i_action_seq
                    AND e.c_event_type = 'DOCUMENT_UPLOADED' AND e.status IN ('PENDING', 'PROCESSING'));

-- Progress: the queue by status.
-- SELECT status, count(*) FROM wdp.ai_response_events WHERE status <> 'TRACKER' GROUP BY status ORDER BY status;

-- Results: each case's latest run.
-- SELECT DISTINCT ON (case_number) case_number, mode, decision, lead_code, disputed_amount, contested_amount, currency,
--        error
-- FROM wdp_ai.classifier_run WHERE case_number IN ('CASE_NUMBER_1', 'CASE_NUMBER_2')
-- ORDER BY case_number, created_at DESC;

-- QA comparisons.
-- SELECT case_number, ops_decision, llm_decision, ops_amount, llm_amount, ops_lead, llm_lead, verdict, verdict_reason
-- FROM wdp_ai.contest_comparison WHERE mode = 'QA_MODE' AND case_number IN ('CASE_NUMBER_1', 'CASE_NUMBER_2');

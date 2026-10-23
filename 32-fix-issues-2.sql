-- Office DB step 32 (wdp_ai): fix-issues stage 2, POC db/V56, V57 and V58 (CL-3022 to CL-3024). PROVISIONAL with the
-- rest of the stage's office steps. Written, not run.
--
-- 1. The decision CONTEST_PARTIAL is CONTEST_MODERATE (V56): every column that stores a run's decision as a value.
--    run_record JSON is history and is left as written.
-- 2. The orchestrator files nothing (V57): classifier_run.questionnaire_ref, the saved questionnaire's id, is never
--    written again.
-- 3. mode leaves the orchestrator (V58): classifier_run.mode and its CHECK go; the mode is the scheduler's RUN row's
--    (wdp.ai_response_events.mode).
--
-- Order: deploy the stage's code first (the orchestrator, the llm's app/ with the ORM ClassifierRun that maps neither
-- column, and the event service), then this step. The old orchestrator writes both columns and would fail on a table
-- without them; the old llm selects both. Export the two columns first if the office keeps such archives, as the POC
-- did (documents/_archive/classifier-run-questionnaire-ref-CL-3023.csv, classifier-run-mode-CL-3024.csv).

BEGIN;

UPDATE wdp_ai.classifier_run SET decision = 'CONTEST_MODERATE' WHERE decision = 'CONTEST_PARTIAL';
UPDATE wdp_ai.contest_comparison SET llm_decision = 'CONTEST_MODERATE' WHERE llm_decision = 'CONTEST_PARTIAL';

ALTER TABLE wdp_ai.classifier_run DROP COLUMN IF EXISTS questionnaire_ref;

ALTER TABLE wdp_ai.classifier_run DROP CONSTRAINT IF EXISTS classifier_run_mode_check;
ALTER TABLE wdp_ai.classifier_run DROP COLUMN IF EXISTS mode;

COMMIT;

-- Check: no old decision value left, and neither column remains
SELECT count(*) AS partial_runs FROM wdp_ai.classifier_run WHERE decision = 'CONTEST_PARTIAL';
SELECT count(*) AS partial_comparisons FROM wdp_ai.contest_comparison WHERE llm_decision = 'CONTEST_PARTIAL';
SELECT column_name FROM information_schema.columns
 WHERE table_schema = 'wdp_ai' AND table_name = 'classifier_run' AND column_name IN ('questionnaire_ref', 'mode');

-- 4. (CL-3029, POC db/V59) the decision MANUAL_REVIEW is UNDECIDED, in the same two columns as 1. Run with 1 to 3.
BEGIN;
UPDATE wdp_ai.classifier_run SET decision = 'UNDECIDED' WHERE decision = 'MANUAL_REVIEW';
UPDATE wdp_ai.contest_comparison SET llm_decision = 'UNDECIDED' WHERE llm_decision = 'MANUAL_REVIEW';
COMMIT;
SELECT count(*) AS manual_review_runs FROM wdp_ai.classifier_run WHERE decision = 'MANUAL_REVIEW';

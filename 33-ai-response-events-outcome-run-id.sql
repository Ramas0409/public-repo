-- Office DB step 33 (wdp), POC db/V60__ai_response_events_outcome_run_id.sql (CL-3030). PROVISIONAL, with step 30.
-- Written, not run. For a table created by step 30 before this step; a table created by step 30 as it now stands
-- already has both columns: skip this step there.
--
-- outcome: what the rule answered - a RUN's decision (UNDECIDED on that FAIL), a COMPARE's verdict where the compare
-- completed else NOT_COMPARED, an OUTCOME's LOST. run_id: the run made or compared; NULL on an OUTCOME. Written with the
-- status whenever the rule answered, SUCCESS or FAIL; NULL where no answer came, on SKIPPED, after a reap and after a
-- reset. Unprefixed like the scheduler's other columns; if the estate's DDL prefixes them, the entity's two @Column
-- names change.

BEGIN;

ALTER TABLE wdp.ai_response_events ADD COLUMN IF NOT EXISTS outcome varchar(64);
ALTER TABLE wdp.ai_response_events ADD COLUMN IF NOT EXISTS run_id bigint;

COMMIT;

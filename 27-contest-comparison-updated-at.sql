-- Office DB step 27 (wdp_ai): contest_comparison.updated_at.
-- The llm service's compare (office-llm-align step 3) runs the POC's upsert, which sets updated_at = now() on every
-- re-compare of a case; the POC added the column in services/comparison-service/db/003. Written, not run.
-- Re-runnable: the column is added only where it is missing, and the back-fill touches only rows it just added.

BEGIN;

ALTER TABLE wdp_ai.contest_comparison ADD COLUMN IF NOT EXISTS updated_at timestamp;
UPDATE wdp_ai.contest_comparison SET updated_at = inserted_at WHERE updated_at IS NULL;
ALTER TABLE wdp_ai.contest_comparison ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE wdp_ai.contest_comparison ALTER COLUMN updated_at SET NOT NULL;

COMMIT;

-- Check: one row, timestamp without time zone, NOT NULL, default now()
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'wdp_ai' AND table_name = 'contest_comparison' AND column_name = 'updated_at';

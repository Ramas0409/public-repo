-- Office step 14: classifier_run records the run's mode and keeps a silent run's network letter (POC db/V43, CL-163).
-- mode is SILENT_MODE or REVIEW_MODE on every row; the rows already there are filled REVIEW_MODE by the default, which
-- is then dropped so the code always names it. network_letter is the base64 PDF a SILENT_MODE run kept instead of
-- uploading it; NULL on every review run. Both letter columns are stored EXTERNAL. Needs step 6 (the questionnaire
-- column) first. Safe to run again.

BEGIN;

ALTER TABLE wdp_ai.classifier_run ADD COLUMN IF NOT EXISTS mode varchar(20) NOT NULL DEFAULT 'REVIEW_MODE';
ALTER TABLE wdp_ai.classifier_run ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE wdp_ai.classifier_run DROP CONSTRAINT IF EXISTS classifier_run_mode_check;
ALTER TABLE wdp_ai.classifier_run ADD CONSTRAINT classifier_run_mode_check
  CHECK (mode IN ('SILENT_MODE', 'REVIEW_MODE'));
ALTER TABLE wdp_ai.classifier_run ADD COLUMN IF NOT EXISTS network_letter text;
ALTER TABLE wdp_ai.classifier_run ALTER COLUMN network_letter SET STORAGE EXTERNAL;
ALTER TABLE wdp_ai.classifier_run ALTER COLUMN ops_letter     SET STORAGE EXTERNAL;

COMMIT;

-- Check: every row has a mode, and no review row holds a letter
SELECT mode, count(*) AS runs, count(network_letter) AS with_network_letter
  FROM wdp_ai.classifier_run GROUP BY mode;

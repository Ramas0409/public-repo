-- Office step 15: the condition-token CHECK on requirement_time_limit is dropped (POC db/V44, CL-165).
-- The vocabulary moves into the orchestrator (ClassifierTimeLimits.CONDITIONS, checked by ArgumentCatalogue.problems())
-- and the seed generator, so a reason code with tokens of its own (13.7, 13.3) needs no constraint migration.
-- The POC's constraint is requirement_time_limit_conditions_check, and step 09-11 added it under that name here; the
-- name was not read from wdp_ai (the office database is not reachable from the POC), so every CHECK on the
-- conditions column is dropped whatever it is called, as step 09-11 did. Every other CHECK stays. Safe to run again.

BEGIN;

DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'wdp_ai.requirement_time_limit'::regclass AND contype = 'c'
                AND pg_get_constraintdef(oid) LIKE '%conditions%'
    LOOP
        EXECUTE format('ALTER TABLE wdp_ai.requirement_time_limit DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

-- expected: no row - the CHECK on conditions is gone; the compare and sentence CHECKs remain
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid = 'wdp_ai.requirement_time_limit'::regclass AND contype = 'c'
   AND pg_get_constraintdef(oid) LIKE '%conditions%';

COMMIT;

-- Office step 4: reason_code as it stands on api-changes (POC db/V2__seed.sql, CL-132). The table's structure is
-- unchanged; the one difference is the catalogue version of VISA 13.1, 1.2.0 -> 1.3.0. Safe to run again.

BEGIN;

UPDATE wdp_ai.reason_code
   SET catalogue_version = '1.3.0'
 WHERE network = 'VISA' AND code = '13.1';

COMMIT;

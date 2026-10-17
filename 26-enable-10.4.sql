-- Office step 26 (branch 1004-code; not run until 10.4 is switched on in the office): VISA 10.4 runs (POC
-- db/dev/enable-10.4.sql, CL-1002). 10.4 is unruled: no SME ruling, and its allocprearb letter wording awaits approval.
UPDATE wdp_ai.reason_code SET enabled = true WHERE network = 'VISA' AND code = '10.4';
SELECT network, code, enabled FROM wdp_ai.reason_code ORDER BY network, code;

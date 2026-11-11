-- UAT/prod step 3 (wdp_ai): which reason codes run in this environment. The seed (step 2) never writes enabled, and
-- the register fingerprint leaves it out, so it is set here, explicitly, per environment. Edit the IN list to the
-- release's plan for this environment (today the office runs VISA 13.1 alone). Safe to run again.

UPDATE wdp_ai.reason_code SET enabled = (network = 'VISA' AND code IN ('13.1'));

SELECT network, code, catalogue_version, enabled FROM wdp_ai.reason_code ORDER BY network, code COLLATE "C";

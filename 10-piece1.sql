-- 10 piece 1 of 3: backup and delete
BEGIN;

CREATE TABLE IF NOT EXISTS wdp_ai.requirement_time_limit_bak_20261006 AS SELECT * FROM wdp_ai.requirement_time_limit;

DELETE FROM wdp_ai.requirement_time_limit;
-- expected: DELETE 7. Leave the transaction open and run piece 2 in the SAME session.

-- Office step 17: the network-neutral register (POC db/V45, CL-172 onwards). Safe to run again.

BEGIN;

-- CL-172: the register decides what runs; VISA 13.1 is on, every other code off until its own script switches it on
ALTER TABLE wdp_ai.reason_code ADD COLUMN IF NOT EXISTS enabled boolean NOT NULL DEFAULT false;
UPDATE wdp_ai.reason_code SET enabled = true WHERE network = 'VISA' AND code = '13.1';

-- CL-173: the response-reason and record-rule-kind vocabularies move into the code, as the conditions did at step 15;
-- every CHECK on those two columns is dropped whatever it is called (the names were not read from wdp_ai)
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
              WHERE contype = 'c'
                AND ((conrelid = 'wdp_ai.requirement'::regclass AND pg_get_constraintdef(oid) LIKE '%response_reason%')
                  OR (conrelid = 'wdp_ai.requirement_record_rule'::regclass AND pg_get_constraintdef(oid) LIKE '%rule_kind%'))
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.tbl, c.conname);
    END LOOP;
END $$;

-- CL-174: a row's role, seeded from its **Role** line (step 20, the regenerated seed, writes the values)
ALTER TABLE wdp_ai.requirement ADD COLUMN IF NOT EXISTS role varchar(32);

-- CL-175: the network's display name; never_mention and banned_phrases on reason_code (written by the regenerated seed)
CREATE TABLE IF NOT EXISTS wdp_ai.network (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         varchar(16) NOT NULL UNIQUE,
    display_name varchar(64) NOT NULL
);
INSERT INTO wdp_ai.network (code, display_name) VALUES ('VISA', 'Visa') ON CONFLICT (code) DO NOTHING;
ALTER TABLE wdp_ai.reason_code ADD COLUMN IF NOT EXISTS never_mention text;
ALTER TABLE wdp_ai.reason_code ADD COLUMN IF NOT EXISTS banned_phrases text[];

COMMIT;

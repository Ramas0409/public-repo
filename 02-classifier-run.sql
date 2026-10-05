-- Office step 2: classifier_run as it stands on api-changes (POC db/V27, V31, V34). The office table holds no rows,
-- so it is dropped and recreated; nothing is backfilled.

BEGIN;

DROP TABLE IF EXISTS wdp_ai.classifier_run;

CREATE TABLE wdp_ai.classifier_run (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform           varchar(16)   NOT NULL,
    case_number        varchar(15)   NOT NULL,
    action_sequence    varchar(2)    NOT NULL,
    created_at         timestamptz   NOT NULL,
    decision           varchar(32),
    lead_code          varchar(16),
    disputed_amount    numeric(18,2),
    contested_amount   numeric(18,2),
    currency           char(3),
    questionnaire_ref  varchar(256),
    error              text,
    run_record         text,
    prompt_snapshot    text,
    ops_letter         text,
    CONSTRAINT classifier_run_case_created_unique UNIQUE (platform, case_number, action_sequence, created_at)
);

COMMIT;

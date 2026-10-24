-- Office DB step 34 (wdp_ai), POC db/V61__model_call_usage.sql (CL-3050). Written, not run: one row per provider
-- attempt of a model call (facts, nominate, compare; re-asks, stalls and refusals included), its tokens and cost,
-- written by the llm service alone, insert only. USAGE_RECORDING_ENABLED=false writes no row; a failed insert is one
-- WARN and never touches the call. Needs no earlier step but the schema. Safe to run again.
--
-- Run it as the role that owns wdp_ai.extracted_fact, so the service's user has the same rights. Run as any other role,
-- the service's user also needs: GRANT SELECT, INSERT ON wdp_ai.model_call_usage TO <the service's
-- wdp_datasource_username>; without it every call logs "usage not recorded: ProgrammingError" and no row is written
-- (the calls themselves are unaffected).
--
-- action_sequence is NULL on nominate rows (its request has none); reason_code and network are NULL on facts and
-- compare rows. cost_usd is NULL for a model MODEL_PRICES does not list: set MODEL_PRICES for the office's model
-- (gpt-5.4) from the provider's current price list before the first run.

BEGIN;

CREATE TABLE IF NOT EXISTS wdp_ai.model_call_usage (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform           varchar(8)   NOT NULL,
    case_number        varchar(15)  NOT NULL,
    action_sequence    varchar(2),
    reason_code        varchar(8),
    network            varchar(16),
    correlation_id     varchar(64)  NOT NULL,
    endpoint           varchar(16)  NOT NULL,
    step               varchar(256) NOT NULL,
    attempt            integer      NOT NULL,
    model              varchar(64)  NOT NULL,
    prompt_tokens      integer,
    cached_tokens      integer,
    completion_tokens  integer,
    reasoning_tokens   integer,
    cost_usd           numeric(12,6),
    status             varchar(16)  NOT NULL,
    tokens_estimated   boolean      NOT NULL DEFAULT false,
    elapsed_ms         integer      NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT model_call_usage_status_check
        CHECK (status IN ('ANSWERED', 'STALLED', 'REFUSED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS model_call_usage_case_idx ON wdp_ai.model_call_usage (platform, case_number, action_sequence);
CREATE INDEX IF NOT EXISTS model_call_usage_corr_idx ON wdp_ai.model_call_usage (correlation_id);
CREATE INDEX IF NOT EXISTS model_call_usage_created_idx ON wdp_ai.model_call_usage (created_at);

COMMIT;

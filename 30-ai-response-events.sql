-- Office DB step 30 (wdp), POC db/auto-response/V3001 on the auto-response branch (V51 at the merge). PROVISIONAL:
-- this is the POC stand-in's DDL. The estate's own table is the entity AIResponseEntity's, with the scheduler's columns
-- in the estate's names and bre_outbox_id as the office's dedupe key (design WDP-AUTO-RESPONSE-SCHEDULER-SOLUTION-DESIGN
-- §4 and §13); replace this step with the estate's DDL once it is written. Written, not run.
--
-- One row per WDP event the scheduler acts on once: the event's fields, the mode on a RUN row, the intake's
-- idempotency id and inserted_at (the outbox row's updated_at), and the scheduler's status, retry count and two
-- timestamps; plus one tracking row (AI-INTAKE-00 / 00, status TRACKER) holding the intake's mark (CL-3010).
-- Inserted, claimed and updated by the event service, which reads wdp.bre_orchestration_outbox itself.
-- No unique index: the intake takes only outbox rows with an idempotency id it has not stored, and a repeated
-- event with a new id is a second row (accepted).
-- Grants the event service's user needs: SELECT, INSERT, UPDATE ON wdp.ai_response_events;
-- SELECT ON wdp.bre_orchestration_outbox. The outbox needs an index on (component, status, updated_at).
-- Not re-runnable as written: CREATE TABLE fails where the table exists.

BEGIN;

CREATE TABLE wdp.ai_response_events (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    i_case           varchar(15)  NOT NULL,
    i_action_seq     varchar(2)   NOT NULL,
    c_acq_platform   varchar(64)  NOT NULL,
    c_case_stage     varchar(64),
    c_action_type    varchar(64),
    c_reason         varchar(8),
    c_case_ntwk      varchar(64),
    c_event_type     varchar(64)  NOT NULL,
    c_doc_type       varchar(64),
    mode             varchar(32),
    idempotency_id   varchar(100),
    inserted_at      timestamp,
    status           varchar(16)  NOT NULL DEFAULT 'PENDING',
    retry_count      integer      NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ai_response_events_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAIL', 'ERROR', 'SKIPPED', 'TRACKER'))
);

CREATE INDEX ai_response_events_claim_idx
    ON wdp.ai_response_events (status, created_at);

CREATE INDEX ai_response_events_case_idx
    ON wdp.ai_response_events (c_acq_platform, i_case, status);

INSERT INTO wdp.ai_response_events (i_case, i_action_seq, c_acq_platform, c_event_type, status)
VALUES ('AI-INTAKE-00', '00', 'NAP', 'INTAKE', 'TRACKER');

COMMIT;

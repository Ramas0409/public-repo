-- UAT/prod step 1b (wdp): the scheduler's queue table wdp.ai_response_events, structure only (office DB steps 30, 31,
-- 33 as they stand: the table with error_reason, outcome and run_id). PROVISIONAL like step 30: skip this file where
-- the environment's estate already provides the table, and compare it with structure-fingerprint.sql instead.
-- Run it as the role that owns the wdp schema's tables. Not re-runnable.

BEGIN;
--
--

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_table_access_method = heap;

--
-- Name: ai_response_events; Type: TABLE; Schema: wdp; Owner: -
--

CREATE TABLE wdp.ai_response_events (
    id bigint NOT NULL,
    i_case character varying(15) NOT NULL,
    i_action_seq character varying(2) NOT NULL,
    c_acq_platform character varying(64) NOT NULL,
    c_case_stage character varying(64),
    c_action_type character varying(64),
    c_reason character varying(8),
    c_case_ntwk character varying(64),
    c_event_type character varying(64) NOT NULL,
    c_doc_type character varying(64),
    mode character varying(32),
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    idempotency_id character varying(100),
    inserted_at timestamp without time zone,
    error_reason character varying(512),
    outcome character varying(64),
    run_id bigint,
    CONSTRAINT ai_response_events_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'SUCCESS'::character varying, 'FAIL'::character varying, 'ERROR'::character varying, 'SKIPPED'::character varying, 'TRACKER'::character varying])::text[])))
);

--
-- Name: ai_response_events_id_seq; Type: SEQUENCE; Schema: wdp; Owner: -
--

ALTER TABLE wdp.ai_response_events ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp.ai_response_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: ai_response_events ai_response_events_pkey; Type: CONSTRAINT; Schema: wdp; Owner: -
--

ALTER TABLE ONLY wdp.ai_response_events
    ADD CONSTRAINT ai_response_events_pkey PRIMARY KEY (id);

--
-- Name: ai_response_events_case_idx; Type: INDEX; Schema: wdp; Owner: -
--

CREATE INDEX ai_response_events_case_idx ON wdp.ai_response_events USING btree (c_acq_platform, i_case, status);

--
-- Name: ai_response_events_claim_idx; Type: INDEX; Schema: wdp; Owner: -
--

CREATE INDEX ai_response_events_claim_idx ON wdp.ai_response_events USING btree (status, created_at);

--
--


COMMIT;

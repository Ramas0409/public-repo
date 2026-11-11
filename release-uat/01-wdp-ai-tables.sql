-- UAT/prod step 1 (wdp_ai): the auto-contest tables, structure only, no rows. Exported from the POC with pg_dump
-- --schema-only --no-owner --no-privileges --no-tablespaces (prompt_baseline, a dev tool table, left out); the POC's
-- structure equals office dev's when structure-fingerprint.sql gives the same rows in both (README, step 0).
-- Run it as the role that is to own wdp_ai. One transaction: all or nothing. Not re-runnable: CREATE TABLE fails
-- where a table exists. No sequence needs a grant: every id is an identity column.

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

--
-- Name: wdp_ai; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS wdp_ai;

SET default_table_access_method = heap;

--
-- Name: case_document; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.case_document (
    document_ref character varying(256) NOT NULL,
    declared_side character varying(16) NOT NULL,
    content_hash character varying(64),
    id bigint NOT NULL,
    dispute_case_id character varying(15) NOT NULL,
    CONSTRAINT case_document_side_check CHECK (((declared_side)::text = ANY ((ARRAY['ISSUER'::character varying, 'MERCHANT'::character varying])::text[])))
);

--
-- Name: case_document_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.case_document ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.case_document_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: classifier_run; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.classifier_run (
    id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    decision character varying(32),
    lead_code character varying(16),
    disputed_amount numeric(18,2),
    contested_amount numeric(18,2),
    currency character(3),
    error text,
    run_record text,
    platform character varying(16) NOT NULL,
    case_number character varying(15) NOT NULL,
    action_sequence character varying(2) NOT NULL,
    prompt_snapshot text,
    ops_letter text,
    questionnaire text,
    network_letter text
);
ALTER TABLE ONLY wdp_ai.classifier_run ALTER COLUMN ops_letter SET STORAGE EXTERNAL;
ALTER TABLE ONLY wdp_ai.classifier_run ALTER COLUMN network_letter SET STORAGE EXTERNAL;

--
-- Name: classifier_run_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.classifier_run ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.classifier_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: contest_comparison; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.contest_comparison (
    id bigint NOT NULL,
    mode character varying(20) NOT NULL,
    platform character varying(10) NOT NULL,
    case_number character varying(15) NOT NULL,
    action_sequence character varying(2) NOT NULL,
    ops_decision character varying(20) NOT NULL,
    llm_decision character varying(20) NOT NULL,
    ops_amount numeric(14,2),
    llm_amount numeric(14,2),
    ops_lead character varying(10),
    llm_lead character varying(10),
    ops_supporting_codes text,
    llm_supporting_codes text,
    llm_run_id bigint,
    changed_fields text,
    verdict character varying(20),
    verdict_reason text,
    network_outcome character varying(10) DEFAULT 'PENDING'::character varying NOT NULL,
    inserted_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);

--
-- Name: contest_comparison_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.contest_comparison ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.contest_comparison_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: document_text; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.document_text (
    page integer NOT NULL,
    text text NOT NULL,
    read_quality character varying(48),
    id bigint NOT NULL,
    content_hash character varying(64) NOT NULL,
    reader_version character varying(32),
    case_document_id bigint NOT NULL,
    CONSTRAINT document_text_page_check CHECK ((page >= 1))
);

--
-- Name: document_text_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.document_text ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.document_text_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: document_token_map; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.document_token_map (
    case_document_id bigint NOT NULL,
    map bytea NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: extracted_fact; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.extracted_fact (
    id bigint NOT NULL,
    status character varying(16) NOT NULL,
    value text,
    page integer,
    quote text,
    read_quality character varying(48),
    question_version character varying(32) NOT NULL,
    extraction_prompt_version character varying(32) NOT NULL,
    provider character varying(32),
    model character varying(64),
    extracted_at timestamp with time zone DEFAULT now() NOT NULL,
    contract_version character varying(32),
    retry_prompt_version text,
    fact_key_id bigint NOT NULL,
    dispute_case_id character varying(15) NOT NULL,
    case_document_id bigint NOT NULL,
    reader_version character varying(32),
    CONSTRAINT extracted_fact_status_check CHECK (((status)::text = ANY ((ARRAY['KNOWN'::character varying, 'JUDGED'::character varying, 'NOT_FOUND'::character varying, 'UNRESOLVED'::character varying])::text[])))
);

--
-- Name: extracted_fact_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.extracted_fact ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.extracted_fact_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: fact_key; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.fact_key (
    key character varying(128) NOT NULL,
    question text,
    type character varying(16) NOT NULL,
    origin character varying(16) NOT NULL,
    source_ref character varying(128),
    source_side character varying(16),
    value_domain character varying(400),
    contract_version character varying(32),
    id bigint NOT NULL,
    label character varying(128),
    CONSTRAINT fact_key_domain_shape CHECK (((value_domain IS NULL) OR ((value_domain)::text ~ '^(ENUM\([A-Z_]+(,[A-Z_]+)*\)|ISO_DATE|MONEY|CARRIER_REF|FREE_TEXT)$'::text))),
    CONSTRAINT fact_key_origin_check CHECK (((origin)::text = ANY ((ARRAY['SYSTEM'::character varying, 'DOCUMENT'::character varying, 'MODEL'::character varying, 'FACTS'::character varying])::text[]))),
    CONSTRAINT fact_key_question_required CHECK ((((origin)::text = 'SYSTEM'::text) OR (question IS NOT NULL))),
    CONSTRAINT fact_key_side_check CHECK (((source_side IS NULL) OR ((source_side)::text = ANY ((ARRAY['MERCHANT'::character varying, 'ISSUER'::character varying, 'EITHER'::character varying])::text[])))),
    CONSTRAINT fact_key_type_check CHECK (((type)::text = ANY ((ARRAY['truth'::character varying, 'value'::character varying, 'measure'::character varying])::text[])))
);

--
-- Name: fact_key_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.fact_key ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.fact_key_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: merchant_document_type; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.merchant_document_type (
    id bigint NOT NULL,
    reason_code_id bigint NOT NULL,
    key character varying(48) NOT NULL,
    label character varying(128) NOT NULL,
    ask_text text NOT NULL,
    why_text text NOT NULL,
    display_order integer NOT NULL
);

--
-- Name: merchant_document_type_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.merchant_document_type ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.merchant_document_type_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: model_call_usage; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.model_call_usage (
    id bigint NOT NULL,
    platform character varying(8) NOT NULL,
    case_number character varying(15) NOT NULL,
    action_sequence character varying(2),
    reason_code character varying(8),
    network character varying(16),
    correlation_id character varying(64) NOT NULL,
    endpoint character varying(16) NOT NULL,
    step character varying(256) NOT NULL,
    attempt integer NOT NULL,
    model character varying(64) NOT NULL,
    prompt_tokens integer,
    cached_tokens integer,
    completion_tokens integer,
    reasoning_tokens integer,
    cost_usd numeric(12,6),
    status character varying(16) NOT NULL,
    tokens_estimated boolean DEFAULT false NOT NULL,
    elapsed_ms integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    cache_write_tokens integer,
    CONSTRAINT model_call_usage_status_check CHECK (((status)::text = ANY ((ARRAY['ANSWERED'::character varying, 'STALLED'::character varying, 'REFUSED'::character varying, 'FAILED'::character varying])::text[])))
);

--
-- Name: model_call_usage_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.model_call_usage ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.model_call_usage_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: qa_ops_questionnaire; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.qa_ops_questionnaire (
    i_qstnnair_id bigint NOT NULL,
    i_case character varying(15) NOT NULL,
    i_action_seq character varying NOT NULL,
    c_case_stage character varying,
    c_qstnnair character varying,
    x_insrt character varying,
    z_insrt timestamp without time zone,
    x_updt character varying,
    z_updt timestamp without time zone,
    n_document_name character varying
);

--
-- Name: qa_ops_questionnaire_i_qstnnair_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.qa_ops_questionnaire ALTER COLUMN i_qstnnair_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.qa_ops_questionnaire_i_qstnnair_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: questionnaire_field; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.questionnaire_field (
    id bigint NOT NULL,
    questionnaire_path_id bigint NOT NULL,
    name character varying(128) NOT NULL,
    label character varying(256) NOT NULL,
    data_type character varying(16) NOT NULL,
    max_length integer,
    required_when character varying(16) NOT NULL,
    display_order integer NOT NULL,
    CONSTRAINT questionnaire_field_required_check CHECK (((required_when)::text = ANY ((ARRAY['ALWAYS'::character varying, 'PART'::character varying, 'WRITE_OFF'::character varying, 'NEVER'::character varying])::text[]))),
    CONSTRAINT questionnaire_field_type_check CHECK (((data_type)::text = ANY ((ARRAY['TEXT'::character varying, 'LONG_TEXT'::character varying, 'AMOUNT'::character varying, 'DATE'::character varying, 'DATE_TIME'::character varying, 'CODE'::character varying, 'BOOLEAN'::character varying, 'SYSTEM'::character varying])::text[])))
);

--
-- Name: questionnaire_field_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.questionnaire_field ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.questionnaire_field_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: questionnaire_option; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.questionnaire_option (
    id bigint NOT NULL,
    network character varying(16) NOT NULL,
    field_name character varying(128) NOT NULL,
    reason_code_id bigint,
    code character varying(32) NOT NULL,
    label character varying(256) NOT NULL,
    display_order integer NOT NULL,
    CONSTRAINT questionnaire_option_network_check CHECK (((network)::text = ANY ((ARRAY['VISA'::character varying, 'MASTERCARD'::character varying, 'AMEX'::character varying, 'DISCOVER'::character varying])::text[])))
);

--
-- Name: questionnaire_option_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.questionnaire_option ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.questionnaire_option_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: questionnaire_path; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.questionnaire_path (
    id bigint NOT NULL,
    network character varying(16) NOT NULL,
    reason_code_id bigint NOT NULL,
    response_type character varying(24) NOT NULL,
    parent_path_id bigint,
    code character varying(16) NOT NULL,
    label character varying(256) NOT NULL,
    sub_form_field character varying(128),
    display_order integer NOT NULL,
    CONSTRAINT questionnaire_path_network_check CHECK (((network)::text = ANY ((ARRAY['VISA'::character varying, 'MASTERCARD'::character varying, 'AMEX'::character varying, 'DISCOVER'::character varying])::text[]))),
    CONSTRAINT questionnaire_path_sub_form_field_check CHECK (((parent_path_id IS NULL) OR (sub_form_field IS NULL))),
    CONSTRAINT questionnaire_path_type_check CHECK (((response_type)::text = ANY ((ARRAY['representment'::character varying, 'precomresp'::character varying, 'prearbresp'::character varying, 'allocprearb'::character varying, 'allocarb'::character varying])::text[])))
);

--
-- Name: questionnaire_path_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.questionnaire_path ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.questionnaire_path_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: reason_code; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.reason_code (
    code character varying(8) NOT NULL,
    name character varying(128) NOT NULL,
    rulebook_edition character varying(32) NOT NULL,
    catalogue_version character varying(16) NOT NULL,
    grounds_header text NOT NULL,
    how_to_read text NOT NULL,
    terms_used text NOT NULL,
    supplementary_header text NOT NULL,
    id bigint NOT NULL,
    network character varying(16) NOT NULL,
    agency_mccs text[] DEFAULT '{}'::text[] NOT NULL,
    agency_mccs_reviewed text[] DEFAULT '{}'::text[] NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    never_mention text,
    banned_phrases text[],
    plain_name character varying(128),
    first_asks text[] DEFAULT '{}'::text[] NOT NULL
);

--
-- Name: reason_code_fact_key; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.reason_code_fact_key (
    id bigint NOT NULL,
    reason_code_id bigint NOT NULL,
    fact_key_id bigint NOT NULL,
    region character varying(8) DEFAULT 'ALL'::character varying NOT NULL,
    CONSTRAINT reason_code_fact_key_region_check CHECK (((region)::text = ANY ((ARRAY['ALL'::character varying, 'US'::character varying, 'EUROPE'::character varying])::text[])))
);

--
-- Name: reason_code_fact_key_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.reason_code_fact_key ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.reason_code_fact_key_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: reason_code_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.reason_code ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.reason_code_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: report_user; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.report_user (
    id bigint NOT NULL,
    email character varying(254) NOT NULL,
    password_hash character varying(72) NOT NULL,
    role character varying(8) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    must_change_password boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(254),
    password_changed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT report_user_email_lower_check CHECK (((email)::text = lower((email)::text))),
    CONSTRAINT report_user_role_check CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'REGULAR'::character varying])::text[])))
);

--
-- Name: report_user_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.report_user ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.report_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: requirement; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.requirement (
    code character varying(16) NOT NULL,
    kind character varying(16) NOT NULL,
    anchor character varying(96) NOT NULL,
    requirement_text text NOT NULL,
    response_reason character varying(48) NOT NULL,
    supplementary boolean DEFAULT false NOT NULL,
    argument_class character varying(16),
    label character varying(128),
    nominate_when text,
    strong_evidence text,
    does_not_fit text,
    render_order integer,
    id bigint NOT NULL,
    reason_code_id bigint NOT NULL,
    region character varying(8) DEFAULT 'ALL'::character varying NOT NULL,
    sub_form_code character varying(16),
    role character varying(32),
    merchant_documents text[] DEFAULT '{}'::text[] NOT NULL,
    CONSTRAINT requirement_argument_class_check CHECK (((argument_class IS NULL) OR ((argument_class)::text = ANY ((ARRAY['JUDGEMENT'::character varying, 'TIME_LIMIT'::character varying, 'RECORD'::character varying])::text[])))),
    CONSTRAINT requirement_argument_class_on_precondition CHECK (((argument_class IS NULL) OR ((kind)::text = 'PRECONDITION'::text))),
    CONSTRAINT requirement_argument_complete CHECK ((((render_order IS NULL) AND (label IS NULL) AND (nominate_when IS NULL) AND (strong_evidence IS NULL) AND (does_not_fit IS NULL)) OR ((render_order IS NOT NULL) AND (label IS NOT NULL) AND (nominate_when IS NOT NULL) AND (strong_evidence IS NOT NULL) AND (does_not_fit IS NOT NULL)))),
    CONSTRAINT requirement_kind_check CHECK (((kind)::text = ANY ((ARRAY['PRECONDITION'::character varying, 'GROUND'::character varying])::text[]))),
    CONSTRAINT requirement_region_check CHECK (((region)::text = ANY ((ARRAY['ALL'::character varying, 'US'::character varying, 'EUROPE'::character varying])::text[]))),
    CONSTRAINT requirement_rendered_precondition_has_class CHECK (((render_order IS NULL) OR ((kind)::text <> 'PRECONDITION'::text) OR (argument_class IS NOT NULL)))
);

--
-- Name: requirement_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.requirement ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.requirement_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: requirement_record_rule; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.requirement_record_rule (
    rule_kind character varying(32) NOT NULL,
    rule_params text[] DEFAULT '{}'::text[] NOT NULL,
    amount_scope character varying(24) NOT NULL,
    filing_sentence text NOT NULL,
    id bigint NOT NULL,
    requirement_id bigint NOT NULL,
    letter_paragraph text,
    CONSTRAINT requirement_record_rule_scope_check CHECK (((amount_scope)::text = ANY ((ARRAY['FULL'::character varying, 'CASH_BACK_PORTION'::character varying])::text[])))
);

--
-- Name: requirement_record_rule_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.requirement_record_rule ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.requirement_record_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: requirement_time_limit; Type: TABLE; Schema: wdp_ai; Owner: -
--

CREATE TABLE wdp_ai.requirement_time_limit (
    compare_op character varying(2) NOT NULL,
    day_limit integer NOT NULL,
    conditions text[] DEFAULT '{}'::text[] NOT NULL,
    filing_sentence text NOT NULL,
    id bigint NOT NULL,
    requirement_id bigint NOT NULL,
    day_count_fact_key_id bigint NOT NULL,
    limit_text text,
    letter_paragraph text,
    CONSTRAINT requirement_time_limit_compare_check CHECK (((compare_op)::text = ANY ((ARRAY['GE'::character varying, 'LE'::character varying, 'LT'::character varying])::text[]))),
    CONSTRAINT requirement_time_limit_sentence_has_count CHECK ((filing_sentence ~~ '%{n}%'::text))
);

--
-- Name: requirement_time_limit_id_seq; Type: SEQUENCE; Schema: wdp_ai; Owner: -
--

ALTER TABLE wdp_ai.requirement_time_limit ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wdp_ai.requirement_time_limit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: case_document case_document_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.case_document
    ADD CONSTRAINT case_document_pkey PRIMARY KEY (id);

--
-- Name: case_document case_document_ref_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.case_document
    ADD CONSTRAINT case_document_ref_unique UNIQUE (dispute_case_id, document_ref);

--
-- Name: classifier_run classifier_run_case_created_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.classifier_run
    ADD CONSTRAINT classifier_run_case_created_unique UNIQUE (platform, case_number, action_sequence, created_at);

--
-- Name: classifier_run classifier_run_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.classifier_run
    ADD CONSTRAINT classifier_run_pkey PRIMARY KEY (id);

--
-- Name: contest_comparison contest_comparison_mode_case_number_action_sequence_key; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.contest_comparison
    ADD CONSTRAINT contest_comparison_mode_case_number_action_sequence_key UNIQUE (mode, case_number, action_sequence);

--
-- Name: contest_comparison contest_comparison_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.contest_comparison
    ADD CONSTRAINT contest_comparison_pkey PRIMARY KEY (id);

--
-- Name: document_text document_text_page_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.document_text
    ADD CONSTRAINT document_text_page_unique UNIQUE (case_document_id, page);

--
-- Name: document_text document_text_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.document_text
    ADD CONSTRAINT document_text_pkey PRIMARY KEY (id);

--
-- Name: document_token_map document_token_map_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.document_token_map
    ADD CONSTRAINT document_token_map_pkey PRIMARY KEY (case_document_id);

--
-- Name: extracted_fact extracted_fact_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.extracted_fact
    ADD CONSTRAINT extracted_fact_pkey PRIMARY KEY (id);

--
-- Name: extracted_fact extracted_fact_replay_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.extracted_fact
    ADD CONSTRAINT extracted_fact_replay_unique UNIQUE (dispute_case_id, case_document_id, fact_key_id, contract_version, question_version, extraction_prompt_version, retry_prompt_version, reader_version);

--
-- Name: fact_key fact_key_key_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.fact_key
    ADD CONSTRAINT fact_key_key_unique UNIQUE (key);

--
-- Name: fact_key fact_key_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.fact_key
    ADD CONSTRAINT fact_key_pkey PRIMARY KEY (id);

--
-- Name: merchant_document_type merchant_document_type_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.merchant_document_type
    ADD CONSTRAINT merchant_document_type_pkey PRIMARY KEY (id);

--
-- Name: merchant_document_type merchant_document_type_reason_code_id_key_key; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.merchant_document_type
    ADD CONSTRAINT merchant_document_type_reason_code_id_key_key UNIQUE (reason_code_id, key);

--
-- Name: model_call_usage model_call_usage_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.model_call_usage
    ADD CONSTRAINT model_call_usage_pkey PRIMARY KEY (id);

--
-- Name: qa_ops_questionnaire qa_ops_questionnaire_case_action_key; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.qa_ops_questionnaire
    ADD CONSTRAINT qa_ops_questionnaire_case_action_key UNIQUE (i_case, i_action_seq);

--
-- Name: qa_ops_questionnaire qa_ops_questionnaire_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.qa_ops_questionnaire
    ADD CONSTRAINT qa_ops_questionnaire_pkey PRIMARY KEY (i_qstnnair_id);

--
-- Name: questionnaire_field questionnaire_field_name_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_field
    ADD CONSTRAINT questionnaire_field_name_unique UNIQUE (questionnaire_path_id, name);

--
-- Name: questionnaire_field questionnaire_field_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_field
    ADD CONSTRAINT questionnaire_field_pkey PRIMARY KEY (id);

--
-- Name: questionnaire_option questionnaire_option_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_option
    ADD CONSTRAINT questionnaire_option_pkey PRIMARY KEY (id);

--
-- Name: questionnaire_option questionnaire_option_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_option
    ADD CONSTRAINT questionnaire_option_unique UNIQUE NULLS NOT DISTINCT (network, field_name, reason_code_id, code);

--
-- Name: questionnaire_path questionnaire_path_code_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_path
    ADD CONSTRAINT questionnaire_path_code_unique UNIQUE (network, reason_code_id, response_type, code);

--
-- Name: questionnaire_path questionnaire_path_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_path
    ADD CONSTRAINT questionnaire_path_pkey PRIMARY KEY (id);

--
-- Name: reason_code_fact_key reason_code_fact_key_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.reason_code_fact_key
    ADD CONSTRAINT reason_code_fact_key_pkey PRIMARY KEY (id);

--
-- Name: reason_code_fact_key reason_code_fact_key_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.reason_code_fact_key
    ADD CONSTRAINT reason_code_fact_key_unique UNIQUE (reason_code_id, fact_key_id);

--
-- Name: reason_code reason_code_network_code_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.reason_code
    ADD CONSTRAINT reason_code_network_code_unique UNIQUE (network, code);

--
-- Name: reason_code reason_code_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.reason_code
    ADD CONSTRAINT reason_code_pkey PRIMARY KEY (id);

--
-- Name: report_user report_user_email_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.report_user
    ADD CONSTRAINT report_user_email_unique UNIQUE (email);

--
-- Name: report_user report_user_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.report_user
    ADD CONSTRAINT report_user_pkey PRIMARY KEY (id);

--
-- Name: requirement requirement_code_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement
    ADD CONSTRAINT requirement_code_unique UNIQUE (reason_code_id, code);

--
-- Name: requirement requirement_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement
    ADD CONSTRAINT requirement_pkey PRIMARY KEY (id);

--
-- Name: requirement_record_rule requirement_record_rule_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement_record_rule
    ADD CONSTRAINT requirement_record_rule_pkey PRIMARY KEY (id);

--
-- Name: requirement_record_rule requirement_record_rule_requirement_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement_record_rule
    ADD CONSTRAINT requirement_record_rule_requirement_unique UNIQUE (requirement_id);

--
-- Name: requirement requirement_render_order_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement
    ADD CONSTRAINT requirement_render_order_unique UNIQUE (reason_code_id, render_order);

--
-- Name: requirement_time_limit requirement_time_limit_pkey; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement_time_limit
    ADD CONSTRAINT requirement_time_limit_pkey PRIMARY KEY (id);

--
-- Name: requirement_time_limit requirement_time_limit_requirement_unique; Type: CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement_time_limit
    ADD CONSTRAINT requirement_time_limit_requirement_unique UNIQUE (requirement_id);

--
-- Name: extracted_fact_case_key_idx; Type: INDEX; Schema: wdp_ai; Owner: -
--

CREATE INDEX extracted_fact_case_key_idx ON wdp_ai.extracted_fact USING btree (dispute_case_id, fact_key_id);

--
-- Name: model_call_usage_case_idx; Type: INDEX; Schema: wdp_ai; Owner: -
--

CREATE INDEX model_call_usage_case_idx ON wdp_ai.model_call_usage USING btree (platform, case_number, action_sequence);

--
-- Name: model_call_usage_corr_idx; Type: INDEX; Schema: wdp_ai; Owner: -
--

CREATE INDEX model_call_usage_corr_idx ON wdp_ai.model_call_usage USING btree (correlation_id);

--
-- Name: model_call_usage_created_idx; Type: INDEX; Schema: wdp_ai; Owner: -
--

CREATE INDEX model_call_usage_created_idx ON wdp_ai.model_call_usage USING btree (created_at);

--
-- Name: document_text document_text_case_document_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.document_text
    ADD CONSTRAINT document_text_case_document_fkey FOREIGN KEY (case_document_id) REFERENCES wdp_ai.case_document(id) ON DELETE CASCADE;

--
-- Name: document_token_map document_token_map_case_document_id_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.document_token_map
    ADD CONSTRAINT document_token_map_case_document_id_fkey FOREIGN KEY (case_document_id) REFERENCES wdp_ai.case_document(id) ON DELETE CASCADE;

--
-- Name: extracted_fact extracted_fact_case_document_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.extracted_fact
    ADD CONSTRAINT extracted_fact_case_document_fkey FOREIGN KEY (case_document_id) REFERENCES wdp_ai.case_document(id) ON DELETE CASCADE;

--
-- Name: extracted_fact extracted_fact_fact_key_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.extracted_fact
    ADD CONSTRAINT extracted_fact_fact_key_fkey FOREIGN KEY (fact_key_id) REFERENCES wdp_ai.fact_key(id);

--
-- Name: merchant_document_type merchant_document_type_reason_code_id_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.merchant_document_type
    ADD CONSTRAINT merchant_document_type_reason_code_id_fkey FOREIGN KEY (reason_code_id) REFERENCES wdp_ai.reason_code(id);

--
-- Name: questionnaire_field questionnaire_field_questionnaire_path_id_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_field
    ADD CONSTRAINT questionnaire_field_questionnaire_path_id_fkey FOREIGN KEY (questionnaire_path_id) REFERENCES wdp_ai.questionnaire_path(id) ON DELETE CASCADE;

--
-- Name: questionnaire_option questionnaire_option_reason_code_id_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_option
    ADD CONSTRAINT questionnaire_option_reason_code_id_fkey FOREIGN KEY (reason_code_id) REFERENCES wdp_ai.reason_code(id);

--
-- Name: questionnaire_path questionnaire_path_parent_path_id_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_path
    ADD CONSTRAINT questionnaire_path_parent_path_id_fkey FOREIGN KEY (parent_path_id) REFERENCES wdp_ai.questionnaire_path(id) ON DELETE CASCADE;

--
-- Name: questionnaire_path questionnaire_path_reason_code_id_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.questionnaire_path
    ADD CONSTRAINT questionnaire_path_reason_code_id_fkey FOREIGN KEY (reason_code_id) REFERENCES wdp_ai.reason_code(id);

--
-- Name: reason_code_fact_key reason_code_fact_key_fact_key_id_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.reason_code_fact_key
    ADD CONSTRAINT reason_code_fact_key_fact_key_id_fkey FOREIGN KEY (fact_key_id) REFERENCES wdp_ai.fact_key(id);

--
-- Name: reason_code_fact_key reason_code_fact_key_reason_code_id_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.reason_code_fact_key
    ADD CONSTRAINT reason_code_fact_key_reason_code_id_fkey FOREIGN KEY (reason_code_id) REFERENCES wdp_ai.reason_code(id) ON DELETE CASCADE;

--
-- Name: requirement requirement_reason_code_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement
    ADD CONSTRAINT requirement_reason_code_fkey FOREIGN KEY (reason_code_id) REFERENCES wdp_ai.reason_code(id);

--
-- Name: requirement_record_rule requirement_record_rule_requirement_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement_record_rule
    ADD CONSTRAINT requirement_record_rule_requirement_fkey FOREIGN KEY (requirement_id) REFERENCES wdp_ai.requirement(id) ON DELETE CASCADE;

--
-- Name: requirement_time_limit requirement_time_limit_day_count_fact_key_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement_time_limit
    ADD CONSTRAINT requirement_time_limit_day_count_fact_key_fkey FOREIGN KEY (day_count_fact_key_id) REFERENCES wdp_ai.fact_key(id);

--
-- Name: requirement_time_limit requirement_time_limit_requirement_fkey; Type: FK CONSTRAINT; Schema: wdp_ai; Owner: -
--

ALTER TABLE ONLY wdp_ai.requirement_time_limit
    ADD CONSTRAINT requirement_time_limit_requirement_fkey FOREIGN KEY (requirement_id) REFERENCES wdp_ai.requirement(id) ON DELETE CASCADE;

--
--


COMMIT;

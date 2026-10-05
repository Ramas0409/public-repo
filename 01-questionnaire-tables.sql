-- Office step 1: the questionnaire definition tables (POC db/V33, CL-138) and their rows (POC db/V2__seed.sql).
-- Needs wdp_ai.reason_code to hold the VISA 13.1 row. Safe to run again: it drops and recreates the three tables.

BEGIN;

DROP TABLE IF EXISTS wdp_ai.questionnaire_wire_spelling;
DROP TABLE IF EXISTS wdp_ai.questionnaire_option;
DROP TABLE IF EXISTS wdp_ai.questionnaire_field;
DROP TABLE IF EXISTS wdp_ai.questionnaire_path;

CREATE TABLE wdp_ai.questionnaire_path (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    network          varchar(16)  NOT NULL,
    reason_code_id   bigint       NOT NULL REFERENCES wdp_ai.reason_code (id),
    response_type    varchar(24)  NOT NULL,
    parent_path_id   bigint       REFERENCES wdp_ai.questionnaire_path (id) ON DELETE CASCADE,
    code             varchar(16)  NOT NULL,
    label            varchar(256) NOT NULL,
    sub_form_field   varchar(128),
    display_order    integer      NOT NULL,
    CONSTRAINT questionnaire_path_network_check CHECK (network IN ('VISA', 'MASTERCARD', 'AMEX', 'DISCOVER')),
    CONSTRAINT questionnaire_path_type_check CHECK (response_type IN
        ('representment', 'precomresp', 'prearbresp', 'allocprearb', 'allocarb')),
    CONSTRAINT questionnaire_path_code_unique UNIQUE (network, reason_code_id, response_type, code),
    CONSTRAINT questionnaire_path_sub_form_field_check CHECK (parent_path_id IS NULL OR sub_form_field IS NULL)
);

CREATE TABLE wdp_ai.questionnaire_field (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    questionnaire_path_id  bigint       NOT NULL REFERENCES wdp_ai.questionnaire_path (id) ON DELETE CASCADE,
    name                   varchar(128) NOT NULL,
    label                  varchar(256) NOT NULL,
    data_type              varchar(16)  NOT NULL,
    max_length             integer,
    required_when          varchar(16)  NOT NULL,
    display_order          integer      NOT NULL,
    CONSTRAINT questionnaire_field_type_check CHECK (data_type IN
        ('TEXT', 'LONG_TEXT', 'AMOUNT', 'DATE', 'CODE', 'BOOLEAN', 'SYSTEM')),
    CONSTRAINT questionnaire_field_required_check CHECK (required_when IN ('ALWAYS', 'PART', 'WRITE_OFF', 'NEVER')),
    CONSTRAINT questionnaire_field_name_unique UNIQUE (questionnaire_path_id, name)
);

CREATE TABLE wdp_ai.questionnaire_option (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    network         varchar(16)  NOT NULL,
    field_name      varchar(128) NOT NULL,
    reason_code_id  bigint       REFERENCES wdp_ai.reason_code (id),
    code            varchar(32)  NOT NULL,
    label           varchar(256) NOT NULL,
    display_order   integer      NOT NULL,
    CONSTRAINT questionnaire_option_network_check CHECK (network IN ('VISA', 'MASTERCARD', 'AMEX', 'DISCOVER')),
    CONSTRAINT questionnaire_option_unique UNIQUE NULLS NOT DISTINCT (network, field_name, reason_code_id, code)
);

-- the forms (§19.1): the base forms first, then the sub-forms that name them
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', NULL, 'CNLD', 'Cardholder No Longer Disputes', NULL, 1);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', NULL, 'CP', 'Credit Processed', NULL, 2);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', NULL, 'ID', 'Invalid Dispute', 'invalidDispute.invalidDisputeReason', 3);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', NULL, 'NL', 'Not Listed Above', NULL, 4);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', (SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'IDRC4', 'Cardholder cancelled before the expected receipt date of the merchandise/services', NULL, 1);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', (SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'IDRC5', 'Merchandise being held at cardholder''s custom agency', NULL, 2);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', (SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'IDRC6', 'Balance of a partial prepayment was not paid and the merchant was willing and able to provide merchandise/services', NULL, 3);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', (SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'IDRC7', 'Cardholder received merchandise at the agreed upon location or by the agreed date/time', NULL, 4);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', (SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'IDRC8', 'Name on manifest of departed flight matches name on purchased itinerary', NULL, 5);
INSERT INTO wdp_ai.questionnaire_path (network, reason_code_id, response_type, parent_path_id, code, label, sub_form_field, display_order) VALUES
  ('VISA', (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), 'representment', (SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'IDRC25', 'Not listed above', NULL, 6);

-- the fields of each form (§19.2), in display order
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'responseReason', 'Response Reason', 'CODE', NULL, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'disputeResponse', 'Dispute', 'CODE', NULL, 'ALWAYS', 2);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'responseAmount', 'Response Amount', 'AMOUNT', NULL, 'ALWAYS', 3);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'reasonForNotFullResponsibility', 'Why are you not accepting?', 'LONG_TEXT', 10000, 'PART', 4);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'ctmAmount', 'CTM Amount', 'AMOUNT', NULL, 'PART', 5);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'ctmTemplate', 'CTM Template', 'CODE', NULL, 'PART', 6);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'writeOffAmount', 'Write-off Amount', 'AMOUNT', NULL, 'NEVER', 7);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'writeOffDirection', 'Write-off Direction', 'CODE', NULL, 'WRITE_OFF', 8);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'writeOffReason', 'Write-off Reason', 'CODE', NULL, 'WRITE_OFF', 9);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'writeOffNote', 'Write-off Note', 'LONG_TEXT', 5000, 'WRITE_OFF', 10);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'explanation', 'Explanation', 'LONG_TEXT', 10000, 'ALWAYS', 11);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'comments', 'Comments', 'LONG_TEXT', 5000, 'NEVER', 12);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CNLD'), 'userId', 'User', 'SYSTEM', NULL, 'ALWAYS', 13);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'responseReason', 'Response Reason', 'CODE', NULL, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'disputeResponse', 'Dispute', 'CODE', NULL, 'ALWAYS', 2);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'responseAmount', 'Response Amount', 'AMOUNT', NULL, 'ALWAYS', 3);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'reasonForNotFullResponsibility', 'Why are you not accepting?', 'LONG_TEXT', 10000, 'PART', 4);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'ctmAmount', 'CTM Amount', 'AMOUNT', NULL, 'PART', 5);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'ctmTemplate', 'CTM Template', 'CODE', NULL, 'PART', 6);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'writeOffAmount', 'Write-off Amount', 'AMOUNT', NULL, 'NEVER', 7);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'writeOffDirection', 'Write-off Direction', 'CODE', NULL, 'WRITE_OFF', 8);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'writeOffReason', 'Write-off Reason', 'CODE', NULL, 'WRITE_OFF', 9);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'writeOffNote', 'Write-off Note', 'LONG_TEXT', 5000, 'WRITE_OFF', 10);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'creditProcessed[].creditAmount', 'Credit Amount', 'AMOUNT', NULL, 'ALWAYS', 11);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'creditProcessed[].creditProcessedDate', 'Credit Date', 'DATE', NULL, 'ALWAYS', 12);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'creditProcessed[].creditCurrency', 'Currency', 'TEXT', 3, 'ALWAYS', 13);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'creditProcessed[].acquirerReferenceNumber', 'ARN', 'TEXT', 30, 'NEVER', 14);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'explanation', 'Explanation', 'LONG_TEXT', 10000, 'ALWAYS', 15);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'comments', 'Comments', 'LONG_TEXT', 5000, 'NEVER', 16);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'CP'), 'userId', 'User', 'SYSTEM', NULL, 'ALWAYS', 17);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'responseReason', 'Response Reason', 'CODE', NULL, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'disputeResponse', 'Dispute', 'CODE', NULL, 'ALWAYS', 2);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'responseAmount', 'Response Amount', 'AMOUNT', NULL, 'ALWAYS', 3);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'reasonForNotFullResponsibility', 'Why are you not accepting?', 'LONG_TEXT', 10000, 'PART', 4);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'ctmAmount', 'CTM Amount', 'AMOUNT', NULL, 'PART', 5);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'ctmTemplate', 'CTM Template', 'CODE', NULL, 'PART', 6);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'writeOffAmount', 'Write-off Amount', 'AMOUNT', NULL, 'NEVER', 7);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'writeOffDirection', 'Write-off Direction', 'CODE', NULL, 'WRITE_OFF', 8);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'writeOffReason', 'Write-off Reason', 'CODE', NULL, 'WRITE_OFF', 9);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'writeOffNote', 'Write-off Note', 'LONG_TEXT', 5000, 'WRITE_OFF', 10);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'invalidDispute.invalidDisputeReason', 'Invalid Dispute Reason', 'CODE', NULL, 'ALWAYS', 11);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'comments', 'Comments', 'LONG_TEXT', 5000, 'NEVER', 12);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'ID'), 'userId', 'User', 'SYSTEM', NULL, 'ALWAYS', 13);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'responseReason', 'Response Reason', 'CODE', NULL, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'disputeResponse', 'Dispute', 'CODE', NULL, 'ALWAYS', 2);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'responseAmount', 'Response Amount', 'AMOUNT', NULL, 'ALWAYS', 3);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'reasonForNotFullResponsibility', 'Why are you not accepting?', 'LONG_TEXT', 10000, 'PART', 4);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'ctmAmount', 'CTM Amount', 'AMOUNT', NULL, 'PART', 5);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'ctmTemplate', 'CTM Template', 'CODE', NULL, 'PART', 6);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'writeOffAmount', 'Write-off Amount', 'AMOUNT', NULL, 'NEVER', 7);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'writeOffDirection', 'Write-off Direction', 'CODE', NULL, 'WRITE_OFF', 8);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'writeOffReason', 'Write-off Reason', 'CODE', NULL, 'WRITE_OFF', 9);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'writeOffNote', 'Write-off Note', 'LONG_TEXT', 5000, 'WRITE_OFF', 10);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'explanation', 'Explanation', 'LONG_TEXT', 10000, 'ALWAYS', 11);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'comments', 'Comments', 'LONG_TEXT', 5000, 'NEVER', 12);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'NL'), 'userId', 'User', 'SYSTEM', NULL, 'ALWAYS', 13);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'IDRC4'), 'explanation', 'Explanation', 'LONG_TEXT', 10000, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'IDRC5'), 'explanation', 'Explanation', 'LONG_TEXT', 10000, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'IDRC6'), 'explanation', 'Explanation', 'LONG_TEXT', 10000, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'IDRC7'), 'explanation', 'Explanation', 'LONG_TEXT', 10000, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'IDRC8'), 'explanation', 'Explanation', 'LONG_TEXT', 10000, 'ALWAYS', 1);
INSERT INTO wdp_ai.questionnaire_field (questionnaire_path_id, name, label, data_type, max_length, required_when, display_order) VALUES
  ((SELECT id FROM wdp_ai.questionnaire_path WHERE network = 'VISA' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND response_type = 'representment' AND code = 'IDRC25'), 'invalidDispute.notListedDisputeInvalidReason', 'Provide the invalid dispute reason', 'LONG_TEXT', 10000, 'ALWAYS', 1);

-- the value-only options (§19.3), by field name; a NULL reason code is every reason code of the network
INSERT INTO wdp_ai.questionnaire_option (network, field_name, reason_code_id, code, label, display_order) VALUES
  ('VISA', 'disputeResponse', NULL, 'DECL', 'Dispute full amount', 1);
INSERT INTO wdp_ai.questionnaire_option (network, field_name, reason_code_id, code, label, display_order) VALUES
  ('VISA', 'disputeResponse', NULL, 'PART', 'Dispute partial amount', 2);
INSERT INTO wdp_ai.questionnaire_option (network, field_name, reason_code_id, code, label, display_order) VALUES
  ('VISA', 'writeOffDirection', NULL, 'DEBIT', 'Debit', 1);
INSERT INTO wdp_ai.questionnaire_option (network, field_name, reason_code_id, code, label, display_order) VALUES
  ('VISA', 'writeOffDirection', NULL, 'CREDIT', 'Credit', 2);
INSERT INTO wdp_ai.questionnaire_option (network, field_name, reason_code_id, code, label, display_order) VALUES
  ('VISA', 'ctmTemplate', NULL, 'DUMMY_CTM_TEMPLATE', 'Placeholder', 1);

COMMIT;

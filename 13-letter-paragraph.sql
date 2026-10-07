-- Office step 13: letter_paragraph on requirement_time_limit and requirement_record_rule (POC db/V42, CL-155,
-- db/V2__seed.sql as regenerated at CL-155). The network letter prints this paragraph for a time-limit or record-check
-- argument; the filing sentence is untouched (the questionnaire and a system-added row still file it). A new nullable
-- column, so no backup: the ALTERs are IF NOT EXISTS and the UPDATEs set the same text again. Needs steps 7 and 10
-- first (the 39 requirement rows and the 10 time-limit rows). Safe to run again.

BEGIN;

ALTER TABLE wdp_ai.requirement_time_limit ADD COLUMN IF NOT EXISTS letter_paragraph text;
COMMENT ON COLUMN wdp_ai.requirement_time_limit.letter_paragraph IS
    'CL-155: the network letter''s paragraph for the row, placeholders kept, filled by the orchestrator';
ALTER TABLE wdp_ai.requirement_record_rule ADD COLUMN IF NOT EXISTS letter_paragraph text;
COMMENT ON COLUMN wdp_ai.requirement_record_rule.letter_paragraph IS
    'CL-155: the network letter''s paragraph for the row, the figures'' placeholders kept, filled by the orchestrator';

UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The transaction was processed on {processDate} and the dispute was processed on {reportedDate}, {n} calendar days later, beyond the 540-calendar-day outer time limit.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G22';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The transaction was processed on {processDate} and the dispute was processed on {reportedDate}, {n} calendar days later. The issuer''s questionnaire specifies no expected delivery or service date ({fx}). The dispute was therefore initiated after the 120-calendar-day time limit.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G32';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The issuer''s questionnaire gives {expectedDate} as the last date the cardholder expected to receive the merchandise or services ({fx}). The dispute was processed on {reportedDate}, {n} calendar days later, after the 120-calendar-day time limit.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G33';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The merchant is a travel agency (merchant category code 4722). The issuer''s questionnaire gives {cancellationDate} as the date the service was cancelled ({fx}) and {bk}. The dispute was processed on {reportedDate}, {n} calendar days later, inside the 30-calendar-day waiting period that applies to travel and ticket agencies.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G25';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The transaction took place on {saleDate} and the dispute was processed on {reportedDate}, {n} calendar days later. The issuer''s questionnaire specifies no expected delivery or service date ({fx}) and {bk}. The dispute was therefore initiated inside the required 15-calendar-day waiting period.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G34';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The issuer''s questionnaire gives {cancellationDate} as the date the merchant cancelled ({fx}) and {bk}. The dispute was processed on {reportedDate}, {n} calendar days later, inside the required 15-calendar-day waiting period.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G36';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The issuer''s questionnaire gives {expectedDate} as the date the cardholder expected to receive the merchandise or services ({fx}) and {bk}. The dispute was processed on {reportedDate}, {n} calendar days before that date, and the filing gives no explanation for initiating the dispute early.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G42';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The issuer''s documents show the services covered by a bonding authority or insurance scheme and give {letterDate} as the date of the scheme''s letter or advice ({fx}). The dispute was processed on {reportedDate}, {n} calendar days later, after the 60-calendar-day time limit that applies where a reimbursement claim was required.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G23';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The merchant is a travel agency (merchant category code 4722). The issuer''s questionnaire gives {cancellationDate} as the date the merchant cancelled ({fx}), and the records do not show an insolvent provider whose services were covered by a bonding or insurance scheme. The dispute was processed on {reportedDate}, {n} calendar days later, inside the 30-calendar-day waiting period that applies to travel and ticket agencies.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G26';
UPDATE wdp_ai.requirement_time_limit t SET letter_paragraph = 'The issuer''s documents show the services covered by a bonding authority or insurance scheme and give {claimDate} as the date the reimbursement claim was submitted to the scheme ({fx}), and do not state that the scheme is insufficient. The dispute was processed on {reportedDate}, {n} calendar days later, inside the required 60-calendar-day waiting period.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G27';
UPDATE wdp_ai.requirement_record_rule t SET letter_paragraph = 'The transaction was an ATM cash disbursement (merchant category code {mcc}), to which Dispute Condition 13.1 does not apply.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G11';
UPDATE wdp_ai.requirement_record_rule t SET letter_paragraph = '{cashBackAmount} of the transaction was a cash-back disbursement, which Dispute Condition 13.1 excludes.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G18';
UPDATE wdp_ai.requirement_record_rule t SET letter_paragraph = 'The transaction was an automated fuel dispenser transaction (merchant category code {mcc}), to which Dispute Condition 13.1 does not apply.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G19';
UPDATE wdp_ai.requirement_record_rule t SET letter_paragraph = 'The issuer''s filing does not certify {missingCertifications}, which the dispute requires.'
  FROM wdp_ai.requirement r WHERE r.id = t.requirement_id AND r.code = 'G37';

COMMIT;

-- check: both counts must equal their row counts (10 and 4)
SELECT (SELECT count(*) FROM wdp_ai.requirement_time_limit) AS time_limits,
       (SELECT count(letter_paragraph) FROM wdp_ai.requirement_time_limit) AS with_paragraph,
       (SELECT count(*) FROM wdp_ai.requirement_record_rule) AS record_rules,
       (SELECT count(letter_paragraph) FROM wdp_ai.requirement_record_rule) AS with_paragraph;

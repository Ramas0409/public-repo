-- Office steps 9 to 11b in ONE transaction (all or nothing), in the only order that satisfies the foreign keys:
--   facts archived and deleted -> the 24 keys upserted -> every time-limit row rewritten (needs the new day-count keys,
--   frees the old ones) -> the links rewritten with their region -> the keys the register no longer carries deleted.
-- Open this file FRESH in the query tool (File > Open - the editor keeps old text). FIRST select lines 8-11 alone and run
-- them (the pre-check); if it shows 0 rows, run the whole file once. If any statement fails nothing is kept: run
-- ROLLBACK; and send me the message.

-- pre-check: the 15 SYSTEM keys CL-150 removed must have no stored fact (expected: 0 rows; any row = STOP)
SELECT fk.key, count(ef.id) AS stored_facts
  FROM wdp_ai.fact_key fk LEFT JOIN wdp_ai.extracted_fact ef ON ef.fact_key_id = fk.id
 WHERE fk.key IN ('credit.settledBeforeDispute', 'credit.exists', 'credit.amount', 'credit.date', 'reversal.settledBeforeDispute', 'reversal.exists', 'reversal.amount', 'reversal.date', 'crypto.isNonFiatOrNftPurchase', 'delivery.expectedDateSpecified', 'transaction.carriesCashBack', 'transaction.isAirlineTransaction', 'transaction.isAtmCashDisbursement', 'transaction.isAutomatedFuelDispenser', 'transaction.cardProductType')
 GROUP BY fk.key HAVING count(ef.id) > 0;

BEGIN;

-- archive, as a table in the database (kept on a second run); note the count
CREATE TABLE IF NOT EXISTS wdp_ai.extracted_fact_archive_cl152_153 AS
SELECT ef.id, ef.dispute_case_id, ef.case_document_id, cd.document_ref, fk.key, ef.status, ef.value, ef.page, ef.quote, ef.read_quality, ef.question_version, ef.contract_version, ef.extraction_prompt_version, ef.retry_prompt_version, ef.provider, ef.model, ef.extracted_at FROM wdp_ai.extracted_fact ef JOIN wdp_ai.fact_key fk ON fk.id = ef.fact_key_id LEFT JOIN wdp_ai.case_document cd ON cd.id = ef.case_document_id WHERE fk.key IN ('cardholder.confirmedResolvedInMerchantRecords', 'service.attendedAtAgreedLocation', 'issuer.describesQualityOfProvidedGoodsOrServices', 'issuer.describesTransactionAsFraudulent', 'issuer.describesComplaintAsFlightNotOperated', 'service.usageEvidencedOnRecord', 'issuer.describesPurchaseBeyondClearingData', 'service.futureServicesAvailable', 'crypto.cardholderComplaintIsPostDeliveryAccessLoss', 'delivery.acknowledgedByCardholder', 'delivery.podAddress', 'delivery.customsHoldingCountryRole', 'delivery.despatchAddress', 'delivery.agreedLocationAddress', 'crypto.deliveredToWalletOnRecord', 'crypto.destinationWalletAddress', 'delivery.podAddressMatchesAgreed', 'crypto.txHashOnRecord', 'crypto.costAtTransactionAmount', 'merchant.certifiesCardholderTookMerchandiseAtSale', 'delivery.podContainsFullAddress', 'airline.flightDeparted', 'return.attemptDate', 'return.attemptedOrCompleted', 'delivery.daysAfterAgreedDate', 'dispute.daysAfterReturnAttempt', 'delivery.status', 'delivery.deliveredDate', 'dispute.reportedDate', 'transaction.processDate', 'transaction.saleDate', 'transaction.amount', 'transaction.disputedAmount', 'transaction.isCardPresent', 'delivery.agreedDeliveryDate', 'service.cancellationDate', 'merchant.isTicketAgencySellingThirdPartyEventTickets') ORDER BY ef.id;
SELECT count(*) AS archived FROM wdp_ai.extracted_fact_archive_cl152_153;

CREATE TABLE IF NOT EXISTS wdp_ai.fact_key_bak_20261006 AS SELECT * FROM wdp_ai.fact_key;

-- (3) delete the archived facts: DELETE n must equal the archived count above
DELETE FROM wdp_ai.extracted_fact
 WHERE fact_key_id IN (SELECT id FROM wdp_ai.fact_key WHERE key IN ('cardholder.confirmedResolvedInMerchantRecords', 'service.attendedAtAgreedLocation', 'issuer.describesQualityOfProvidedGoodsOrServices', 'issuer.describesTransactionAsFraudulent', 'issuer.describesComplaintAsFlightNotOperated', 'service.usageEvidencedOnRecord', 'issuer.describesPurchaseBeyondClearingData', 'service.futureServicesAvailable', 'crypto.cardholderComplaintIsPostDeliveryAccessLoss', 'delivery.acknowledgedByCardholder', 'delivery.podAddress', 'delivery.customsHoldingCountryRole', 'delivery.despatchAddress', 'delivery.agreedLocationAddress', 'crypto.deliveredToWalletOnRecord', 'crypto.destinationWalletAddress', 'delivery.podAddressMatchesAgreed', 'crypto.txHashOnRecord', 'crypto.costAtTransactionAmount', 'merchant.certifiesCardholderTookMerchandiseAtSale', 'delivery.podContainsFullAddress', 'airline.flightDeparted', 'return.attemptDate', 'return.attemptedOrCompleted', 'delivery.daysAfterAgreedDate', 'dispute.daysAfterReturnAttempt', 'delivery.status', 'delivery.deliveredDate', 'dispute.reportedDate', 'transaction.processDate', 'transaction.saleDate', 'transaction.amount', 'transaction.disputedAmount', 'transaction.isCardPresent', 'delivery.agreedDeliveryDate', 'service.cancellationDate', 'merchant.isTicketAgencySellingThirdPartyEventTickets'));

-- fact keys (§11.1-§11.6a), upserted on the key; the label column is authored nowhere yet
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('dispute.daysAfterBondingClaimSubmitted', NULL, 'measure', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('dispute.daysAfterBondingLetterOrAdvice', NULL, 'measure', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('dispute.daysAfterExpectedReceiptDate', NULL, 'measure', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('dispute.daysAfterServiceCancellation', NULL, 'measure', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('dispute.daysAfterTransactionDate', NULL, 'measure', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('dispute.daysAfterTransactionProcessing', NULL, 'measure', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('transaction.posEnvironment', NULL, 'value', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('transaction.cashBackAmount', NULL, 'measure', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('transaction.channel', NULL, 'value', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('transaction.entryMode', NULL, 'value', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('transaction.mcc', NULL, 'value', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('transaction.merchantCountry', NULL, 'value', 'SYSTEM', NULL, NULL, NULL, 'c6e340b9cff', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('cardholder.attemptedResolutionWithMerchant', 'Did the Cardholder attempt to resolve the dispute with the Merchant, the Merchant''s liquidator, the Ramp Provider, or its Conversion Affiliate as applicable, before the Issuer initiated the Dispute? Answer only from the response recorded against that question. **NOT_FOUND where the form does not ask it or the field is blank — never FALSE.** An answer recorded against a different question is not an answer to this one.', 'truth', 'DOCUMENT', 'issuerDisputeQuestionnaire', 'ISSUER', NULL, 'c65e1404f84', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('cardholder.expectedReceiptDate', 'What is the last date the cardholder expected to receive the merchandise or services, as stated on the issuer''s questionnaire ("last expected receipt date")? ISO date (YYYY-MM-DD); NOT_FOUND if absent.', 'value', 'DOCUMENT', 'issuerDisputeQuestionnaire', 'ISSUER', 'ISO_DATE', 'c3db60f279a', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('issuer.certifiesCardholderCancelledPriorToExpectedDate', 'Does the issuer''s questionnaire answer "Yes" to whether the cardholder cancelled prior to the expected delivery or receipt date? TRUE for Yes, FALSE for No; NOT_FOUND if the form does not ask it or leaves it blank.', 'truth', 'DOCUMENT', 'issuerDisputeQuestionnaire', 'ISSUER', NULL, 'c09c721199b', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('issuer.certifiesMerchantCancelled', 'Does the issuer''s questionnaire answer "Yes" to whether the merchant cancelled the merchandise or services? TRUE for Yes, FALSE for No; NOT_FOUND if the form does not ask it or leaves it blank.', 'truth', 'DOCUMENT', 'issuerDisputeQuestionnaire', 'ISSUER', NULL, 'ccac6f87347', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('issuer.certifiesMerchantInsolvent', 'Does the issuer''s questionnaire answer "Yes" to whether the merchant is in bankruptcy or liquidation? TRUE for Yes, FALSE for No; NOT_FOUND if the form does not ask it or leaves it blank. This is the issuer''s assertion only, not a public insolvency record.', 'truth', 'DOCUMENT', 'issuerDisputeQuestionnaire', 'ISSUER', NULL, 'c5cd561bc1b', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('issuer.certifiesNonFiatNotDeliveredToWallet', 'Does the issuer''s questionnaire answer "Yes" to whether the non-fiat currency or non-fungible token(s) were not successfully delivered to the destination wallet address supplied by the cardholder at the time of the transaction?', 'truth', 'DOCUMENT', 'issuerDisputeQuestionnaire', 'ISSUER', NULL, 'c6bfbb4d5ee', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('issuer.merchantCancellationDate', 'What date does the issuer''s questionnaire give as the date the merchant cancelled the merchandise or services ("Date merchant cancelled services")? ISO date (YYYY-MM-DD); NOT_FOUND if absent or if the form records no cancellation.', 'value', 'DOCUMENT', 'issuerDisputeQuestionnaire', 'ISSUER', 'ISO_DATE', 'cabae298f53', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('bonding.letterOrAdviceDate', 'What date is shown on the letter or advice from the bonding authority or insurance scheme about this claim? ISO date (YYYY-MM-DD); NOT_FOUND if absent.', 'value', 'DOCUMENT', 'bondingSchemeRecord', 'ISSUER', NULL, 'c8bd77b0414', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('bonding.reimbursementClaimSubmitted', 'Does the document show that the Cardholder, the traveler, or the Issuer on their behalf submitted a reimbursement claim or request to the bonding authority or insurance scheme for the disputed travel services? TRUE where a claim, request or the scheme''s acknowledgment of one is shown; FALSE where the document states no claim was made; NOT_FOUND where the document does not say.', 'truth', 'DOCUMENT', 'bondingSchemeRecord', 'ISSUER', NULL, 'cb6858a0d5e', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('bonding.reimbursementClaimSubmittedDate', 'What date does the document show the reimbursement claim or request was submitted to the bonding authority or insurance scheme? ISO date (YYYY-MM-DD); NOT_FOUND if absent.', 'value', 'DOCUMENT', 'bondingSchemeRecord', 'ISSUER', NULL, 'c477b10950d', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('bonding.schemeInsufficient', 'Do the documents show that the bond or insurance scheme is insufficient to reimburse the claimed travel-services loss? Read the scheme''s own letter or advice as well as the issuer''s statement of the advice it was given: express advice that no funds or no bond is available, that limits are exhausted, or that the loss will not be covered means insufficient — TRUE. FALSE where the scheme says it will pay or has paid. NOT_FOUND where no document addresses sufficiency: a pending claim, an acknowledgment or an absence of response is not insufficiency.', 'truth', 'DOCUMENT', 'bondingSchemeRecord', 'ISSUER', NULL, 'c0263314ce2', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
INSERT INTO wdp_ai.fact_key (key, question, type, origin, source_ref, source_side, value_domain, contract_version, label) VALUES
  ('travel.servicesCoveredByBondingOrInsuranceScheme', 'Does the document show that the disputed travel services were covered by a bonding authority or insurance scheme — insolvency protection for the booking, for example ATOL, ABTA or a travel-industry bond? The cardholder''s own travel insurance is not such a scheme. TRUE where the document states the cover or is the scheme''s own correspondence about this booking; FALSE where it states the services were not covered; NOT_FOUND where the document does not address cover.', 'truth', 'DOCUMENT', 'bondingSchemeRecord', 'ISSUER', NULL, 'cff777865bc', NULL)
ON CONFLICT (key) DO UPDATE SET question = EXCLUDED.question, type = EXCLUDED.type, origin = EXCLUDED.origin, source_ref = EXCLUDED.source_ref, source_side = EXCLUDED.source_side, value_domain = EXCLUDED.value_domain, contract_version = EXCLUDED.contract_version, label = EXCLUDED.label;
-- the deletion of the keys the register no longer carries is step 11b, after the time limits and the links
-- have been rewritten: until then the old time-limit rows still reference old day-count keys

CREATE TABLE IF NOT EXISTS wdp_ai.requirement_time_limit_bak_20261006 AS SELECT * FROM wdp_ai.requirement_time_limit;

-- the old rows go FIRST: they still carry mccIs4722 / mccNot4722, which the new CHECK would refuse
-- every row: this table is a pure register table of VISA 13.1 alone, rewritten whole (the backup above keeps
-- what was there); a scoped delete left rows behind on the office database
DELETE FROM wdp_ai.requirement_time_limit;

-- then every CHECK on the conditions column, whatever the office named it, is dropped and the ten-token one added
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'wdp_ai.requirement_time_limit'::regclass AND contype = 'c'
                AND pg_get_constraintdef(oid) LIKE '%conditions%'
    LOOP
        EXECUTE format('ALTER TABLE wdp_ai.requirement_time_limit DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE wdp_ai.requirement_time_limit ADD CONSTRAINT requirement_time_limit_conditions_check
    CHECK (conditions <@ ARRAY[
        'expectedDate', 'noExpectedDate', 'bankruptNotYes', 'cancellationKnown', 'mccIsAgency', 'mccNotAgency',
        'coveredByScheme', 'coveredNotYes', 'schemeInsufficientNotYes', 'notBondedInsolvency'
    ]::text[]);
-- the time-limit rules (§18): one row per TIME_LIMIT argument; absence means not a time limit
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G22'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterTransactionProcessing'), 'GE', 541, ARRAY['expectedDate']::text[], '541 or more days', 'In addition, the dispute was raised {n} calendar days after the transaction processing date, beyond the 540-day outer time limit for this reason code.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G25'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterServiceCancellation'), 'LE', 29, ARRAY['mccIsAgency','cancellationKnown','bankruptNotYes']::text[], '29 or fewer days', 'In addition, the dispute was raised {n} calendar days after the service provider cancelled, within the 30-day waiting period that applies to travel and ticket agencies.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G26'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterServiceCancellation'), 'LE', 29, ARRAY['mccIsAgency','cancellationKnown','notBondedInsolvency']::text[], '29 or fewer days', 'In addition, the dispute was raised {n} calendar days after the merchant cancelled, within the 30-day waiting period that applies to travel and ticket agencies.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G32'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterTransactionProcessing'), 'GE', 121, ARRAY['noExpectedDate','coveredNotYes']::text[], '121 or more days', 'In addition, the dispute was raised {n} calendar days after the transaction processing date, beyond the 120-day time limit for this reason code.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G33'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterExpectedReceiptDate'), 'GE', 121, ARRAY['expectedDate','coveredNotYes']::text[], '121 or more days', 'In addition, the dispute was raised {n} calendar days after the last date the cardholder expected to receive the merchandise or services, beyond the 120-day time limit for this reason code.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G23'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterBondingLetterOrAdvice'), 'GE', 61, ARRAY['coveredByScheme']::text[], '61 or more days', 'In addition, the dispute was processed {n} calendar days after the date of the letter or advice from the bonding authority or insurance scheme, beyond the 60-day time limit that applies where a reimbursement claim was required.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G34'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterTransactionDate'), 'LE', 14, ARRAY['noExpectedDate','bankruptNotYes']::text[], '14 or fewer days', 'In addition, the dispute was raised {n} calendar days after the transaction date, within the 15-day waiting period that applies where no expected date was given.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G36'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterServiceCancellation'), 'LE', 14, ARRAY['cancellationKnown','mccNotAgency','bankruptNotYes']::text[], '14 or fewer days', 'In addition, the dispute was raised {n} calendar days after the merchant cancelled, within the required 15-day waiting period.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G27'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterBondingClaimSubmitted'), 'LE', 59, ARRAY['coveredByScheme','schemeInsufficientNotYes']::text[], '59 or fewer days', 'In addition, the dispute was raised {n} calendar days after the reimbursement claim was submitted to the bonding authority or insurance scheme, inside the 60-day waiting period the rule requires.');
INSERT INTO wdp_ai.requirement_time_limit (requirement_id, day_count_fact_key_id, compare_op, day_limit, conditions, limit_text, filing_sentence) VALUES
  ((SELECT r.id FROM wdp_ai.requirement r WHERE r.reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') AND r.code = 'G42'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterExpectedReceiptDate'), 'LT', 0, ARRAY['bankruptNotYes']::text[], 'below 0 days (raised before the expected date)', 'In addition, the dispute was raised {n} calendar days before the date the cardholder expected to receive the merchandise or services, and the issuer''s filing gives no reason for disputing early.');

ALTER TABLE wdp_ai.reason_code_fact_key ADD COLUMN IF NOT EXISTS region varchar(8) NOT NULL DEFAULT 'ALL';
ALTER TABLE wdp_ai.reason_code_fact_key DROP CONSTRAINT IF EXISTS reason_code_fact_key_region_check;
ALTER TABLE wdp_ai.reason_code_fact_key ADD CONSTRAINT reason_code_fact_key_region_check CHECK (region IN ('ALL', 'US', 'EUROPE'));
COMMENT ON COLUMN wdp_ai.reason_code_fact_key.region IS
    'CL-148: the region of the rows that read the key - ALL, or EUROPE/US where only rows of that region do; the issuer-facts reader asks a DOCUMENT key only on a case whose region includes it';

DELETE FROM wdp_ai.reason_code_fact_key WHERE reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1');

-- the keys linked to the reason code (§18): the issuer-form keys, the bonding keys (EUROPE, CL-148), the day counts, the record fields; the region says on which cases the issuer-facts reader asks a DOCUMENT key
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'cardholder.attemptedResolutionWithMerchant'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'cardholder.expectedReceiptDate'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'issuer.certifiesCardholderCancelledPriorToExpectedDate'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'issuer.certifiesMerchantCancelled'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'issuer.certifiesMerchantInsolvent'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'issuer.certifiesNonFiatNotDeliveredToWallet'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'issuer.merchantCancellationDate'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'bonding.letterOrAdviceDate'), 'EUROPE');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'bonding.reimbursementClaimSubmitted'), 'EUROPE');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'bonding.reimbursementClaimSubmittedDate'), 'EUROPE');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'bonding.schemeInsufficient'), 'EUROPE');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'travel.servicesCoveredByBondingOrInsuranceScheme'), 'EUROPE');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterBondingClaimSubmitted'), 'EUROPE');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterBondingLetterOrAdvice'), 'EUROPE');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterExpectedReceiptDate'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterServiceCancellation'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterTransactionDate'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'dispute.daysAfterTransactionProcessing'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'transaction.mcc'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'transaction.cashBackAmount'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'transaction.merchantCountry'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'transaction.channel'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'transaction.entryMode'), 'ALL');
INSERT INTO wdp_ai.reason_code_fact_key (reason_code_id, fact_key_id, region) VALUES
  ((SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'), (SELECT id FROM wdp_ai.fact_key WHERE key = 'transaction.posEnvironment'), 'ALL');

-- what still references a key that is about to go: expected 0 rows
SELECT fk.id, fk.key, 'requirement_time_limit' AS referenced_by FROM wdp_ai.fact_key fk
 WHERE fk.key NOT IN ('transaction.posEnvironment', 'dispute.daysAfterTransactionProcessing', 'dispute.daysAfterTransactionDate', 'dispute.daysAfterExpectedReceiptDate', 'dispute.daysAfterServiceCancellation', 'dispute.daysAfterBondingClaimSubmitted', 'dispute.daysAfterBondingLetterOrAdvice', 'transaction.mcc', 'transaction.channel', 'transaction.entryMode', 'transaction.cashBackAmount', 'transaction.merchantCountry', 'issuer.certifiesCardholderCancelledPriorToExpectedDate', 'cardholder.expectedReceiptDate', 'issuer.certifiesMerchantInsolvent', 'issuer.certifiesMerchantCancelled', 'issuer.merchantCancellationDate', 'issuer.certifiesNonFiatNotDeliveredToWallet', 'cardholder.attemptedResolutionWithMerchant', 'travel.servicesCoveredByBondingOrInsuranceScheme', 'bonding.reimbursementClaimSubmitted', 'bonding.reimbursementClaimSubmittedDate', 'bonding.schemeInsufficient', 'bonding.letterOrAdviceDate')
   AND EXISTS (SELECT 1 FROM wdp_ai.requirement_time_limit t WHERE t.day_count_fact_key_id = fk.id)
UNION ALL
SELECT fk.id, fk.key, 'reason_code_fact_key' FROM wdp_ai.fact_key fk
 WHERE fk.key NOT IN ('transaction.posEnvironment', 'dispute.daysAfterTransactionProcessing', 'dispute.daysAfterTransactionDate', 'dispute.daysAfterExpectedReceiptDate', 'dispute.daysAfterServiceCancellation', 'dispute.daysAfterBondingClaimSubmitted', 'dispute.daysAfterBondingLetterOrAdvice', 'transaction.mcc', 'transaction.channel', 'transaction.entryMode', 'transaction.cashBackAmount', 'transaction.merchantCountry', 'issuer.certifiesCardholderCancelledPriorToExpectedDate', 'cardholder.expectedReceiptDate', 'issuer.certifiesMerchantInsolvent', 'issuer.certifiesMerchantCancelled', 'issuer.merchantCancellationDate', 'issuer.certifiesNonFiatNotDeliveredToWallet', 'cardholder.attemptedResolutionWithMerchant', 'travel.servicesCoveredByBondingOrInsuranceScheme', 'bonding.reimbursementClaimSubmitted', 'bonding.reimbursementClaimSubmittedDate', 'bonding.schemeInsufficient', 'bonding.letterOrAdviceDate')
   AND EXISTS (SELECT 1 FROM wdp_ai.reason_code_fact_key l WHERE l.fact_key_id = fk.id)
UNION ALL
SELECT fk.id, fk.key, 'extracted_fact' FROM wdp_ai.fact_key fk
 WHERE fk.key NOT IN ('transaction.posEnvironment', 'dispute.daysAfterTransactionProcessing', 'dispute.daysAfterTransactionDate', 'dispute.daysAfterExpectedReceiptDate', 'dispute.daysAfterServiceCancellation', 'dispute.daysAfterBondingClaimSubmitted', 'dispute.daysAfterBondingLetterOrAdvice', 'transaction.mcc', 'transaction.channel', 'transaction.entryMode', 'transaction.cashBackAmount', 'transaction.merchantCountry', 'issuer.certifiesCardholderCancelledPriorToExpectedDate', 'cardholder.expectedReceiptDate', 'issuer.certifiesMerchantInsolvent', 'issuer.certifiesMerchantCancelled', 'issuer.merchantCancellationDate', 'issuer.certifiesNonFiatNotDeliveredToWallet', 'cardholder.attemptedResolutionWithMerchant', 'travel.servicesCoveredByBondingOrInsuranceScheme', 'bonding.reimbursementClaimSubmitted', 'bonding.reimbursementClaimSubmittedDate', 'bonding.schemeInsufficient', 'bonding.letterOrAdviceDate')
   AND EXISTS (SELECT 1 FROM wdp_ai.extracted_fact ef WHERE ef.fact_key_id = fk.id);

DELETE FROM wdp_ai.fact_key WHERE key NOT IN ('transaction.posEnvironment', 'dispute.daysAfterTransactionProcessing', 'dispute.daysAfterTransactionDate', 'dispute.daysAfterExpectedReceiptDate', 'dispute.daysAfterServiceCancellation', 'dispute.daysAfterBondingClaimSubmitted', 'dispute.daysAfterBondingLetterOrAdvice', 'transaction.mcc', 'transaction.channel', 'transaction.entryMode', 'transaction.cashBackAmount', 'transaction.merchantCountry', 'issuer.certifiesCardholderCancelledPriorToExpectedDate', 'cardholder.expectedReceiptDate', 'issuer.certifiesMerchantInsolvent', 'issuer.certifiesMerchantCancelled', 'issuer.merchantCancellationDate', 'issuer.certifiesNonFiatNotDeliveredToWallet', 'cardholder.attemptedResolutionWithMerchant', 'travel.servicesCoveredByBondingOrInsuranceScheme', 'bonding.reimbursementClaimSubmitted', 'bonding.reimbursementClaimSubmittedDate', 'bonding.schemeInsufficient', 'bonding.letterOrAdviceDate')
  AND id NOT IN (SELECT fact_key_id FROM wdp_ai.extracted_fact);   -- expected DELETE 52

COMMIT;

-- after the COMMIT, one row (pgAdmin shows only the LAST result of a script, so the checks are one query):
-- expected  fact_keys 24 | unlinked 0 | orphan_facts 0 | links_all 17 | links_europe 7 | time_limits 10
SELECT (SELECT count(*) FROM wdp_ai.fact_key) AS fact_keys,
       (SELECT count(*) FROM wdp_ai.fact_key fk
         WHERE NOT EXISTS (SELECT 1 FROM wdp_ai.reason_code_fact_key l WHERE l.fact_key_id = fk.id)) AS unlinked,
       (SELECT count(*) FROM wdp_ai.extracted_fact ef
         WHERE NOT EXISTS (SELECT 1 FROM wdp_ai.fact_key fk WHERE fk.id = ef.fact_key_id)) AS orphan_facts,
       (SELECT count(*) FROM wdp_ai.reason_code_fact_key WHERE region = 'ALL') AS links_all,
       (SELECT count(*) FROM wdp_ai.reason_code_fact_key WHERE region = 'EUROPE') AS links_europe,
       (SELECT count(*) FROM wdp_ai.requirement_time_limit) AS time_limits,
       (SELECT count(*) FROM wdp_ai.extracted_fact_archive_cl152_153) AS archived_facts;

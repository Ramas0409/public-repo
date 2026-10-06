-- Office step 11: reason_code_fact_key gains a region (POC db/V38, CL-148) and the 24 links of VISA 13.1 are
-- rewritten whole with it - 17 ALL, 7 EUROPE (the five bonding keys and their two day counts). The issuer-facts
-- reader asks a DOCUMENT key only where the case's region includes its link region. Needs step 9 first. Safe to
-- run again.

BEGIN;

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

COMMIT;

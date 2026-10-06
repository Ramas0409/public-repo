-- Office step 11b: delete the fact keys the register no longer carries - AFTER steps 10 and 11, which rewrote the
-- time-limit rows and the links so that nothing references an old key any more. Step 9 used to end with this and
-- failed on the office database: an old requirement_time_limit row still referenced a day-count key going out
-- (foreign key requirement_time_limit_fact_key_fkey). The guard keeps any key a stored fact still names.

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

BEGIN;

DELETE FROM wdp_ai.fact_key WHERE key NOT IN ('transaction.posEnvironment', 'dispute.daysAfterTransactionProcessing', 'dispute.daysAfterTransactionDate', 'dispute.daysAfterExpectedReceiptDate', 'dispute.daysAfterServiceCancellation', 'dispute.daysAfterBondingClaimSubmitted', 'dispute.daysAfterBondingLetterOrAdvice', 'transaction.mcc', 'transaction.channel', 'transaction.entryMode', 'transaction.cashBackAmount', 'transaction.merchantCountry', 'issuer.certifiesCardholderCancelledPriorToExpectedDate', 'cardholder.expectedReceiptDate', 'issuer.certifiesMerchantInsolvent', 'issuer.certifiesMerchantCancelled', 'issuer.merchantCancellationDate', 'issuer.certifiesNonFiatNotDeliveredToWallet', 'cardholder.attemptedResolutionWithMerchant', 'travel.servicesCoveredByBondingOrInsuranceScheme', 'bonding.reimbursementClaimSubmitted', 'bonding.reimbursementClaimSubmittedDate', 'bonding.schemeInsufficient', 'bonding.letterOrAdviceDate')
  AND id NOT IN (SELECT fact_key_id FROM wdp_ai.extracted_fact);   -- expected DELETE 52

COMMIT;

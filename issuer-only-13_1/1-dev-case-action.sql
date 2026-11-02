-- Run in DEV (office). Adds four synthetic Visa 13.1 NAP cases and their action '01' to nap.case and nap.action:
-- issuer documents only, no merchant pack. Ids are dev's max + 1; a case or action already there is left alone.
-- Rows are marked x_insrt = 'POC_SEED' and c_case_src = 'SYNTHETIC' (clean-up at the end).
--   POCVISA13S06  G15  cardholder says they did not make the purchase      GBP 59.99
--   POCVISA13S6A  G15  the same, a second merchant                          EUR 28.94
--   POCVISA13S7A  G16  goods arrived but cracked and the wrong size         EUR 28.94
--   POCVISA13S03  G13  issuer form: cardholder cancelled before due date    HUF 25095.67
BEGIN;

-- POCVISA13S03
INSERT INTO nap."case" (i_case_id, i_case, c_acq_platform, c_case_ntwk, c_case_src, c_ntwk_case_id, i_acq_refnce_num, c_merchant_name, c_cntry, d_tr, d_prcss_tr, a_purchase_amt, c_purchase_currency, i_purchase_amt_exponent, c_auth_tr, c_mcc, a_csh_back, i_acct_cdh_lst, x_moto, c_entry_mode, x_insrt, z_insrt)
SELECT coalesce(max(i_case_id), 0) + 1, 'POCVISA13S03', 'NAP', 'VISA', 'SYNTHETIC', '5466012934', '74070555212920008000057', 'LUSA 2209999933832', NULL, DATE '2025-07-29', DATE '2025-07-31', 24200.00, 'HUF', 2, '732015', '3008', NULL, '1111', NULL, NULL, 'POC_SEED', now() FROM nap."case"
HAVING NOT EXISTS (SELECT 1 FROM nap."case" WHERE i_case = 'POCVISA13S03');
INSERT INTO nap.action (i_action_id, i_case_id, i_action_seq, i_case, c_reason, c_action_sta, d_action_reported, d_response_due, a_dispute_amt, c_dispute_currency, i_dispute_currency_exponent, x_insrt, z_insrt)
SELECT (SELECT coalesce(max(i_action_id), 0) + 1 FROM nap.action), c.i_case_id, '01', 'POCVISA13S03', '13.1', 'OPEN', DATE '2025-08-01', NULL, 25095.67, 'HUF', 2, 'POC_SEED', now()
FROM nap."case" c WHERE c.i_case = 'POCVISA13S03'
AND NOT EXISTS (SELECT 1 FROM nap.action a WHERE a.i_case = 'POCVISA13S03' AND a.i_action_seq = '01');

-- POCVISA13S06
INSERT INTO nap."case" (i_case_id, i_case, c_acq_platform, c_case_ntwk, c_case_src, c_ntwk_case_id, i_acq_refnce_num, c_merchant_name, c_cntry, d_tr, d_prcss_tr, a_purchase_amt, c_purchase_currency, i_purchase_amt_exponent, c_auth_tr, c_mcc, a_csh_back, i_acct_cdh_lst, x_moto, c_entry_mode, x_insrt, z_insrt)
SELECT coalesce(max(i_case_id), 0) + 1, 'POCVISA13S06', 'NAP', 'VISA', 'SYNTHETIC', '5466202337', '74045445200920010384895', 'SMITHS TOY', NULL, DATE '2025-07-18', DATE '2025-07-19', 59.99, 'GBP', 2, '718802', '5945', NULL, '1111', NULL, NULL, 'POC_SEED', now() FROM nap."case"
HAVING NOT EXISTS (SELECT 1 FROM nap."case" WHERE i_case = 'POCVISA13S06');
INSERT INTO nap.action (i_action_id, i_case_id, i_action_seq, i_case, c_reason, c_action_sta, d_action_reported, d_response_due, a_dispute_amt, c_dispute_currency, i_dispute_currency_exponent, x_insrt, z_insrt)
SELECT (SELECT coalesce(max(i_action_id), 0) + 1 FROM nap.action), c.i_case_id, '01', 'POCVISA13S06', '13.1', 'OPEN', DATE '2025-08-02', NULL, 59.99, 'GBP', 2, 'POC_SEED', now()
FROM nap."case" c WHERE c.i_case = 'POCVISA13S06'
AND NOT EXISTS (SELECT 1 FROM nap.action a WHERE a.i_case = 'POCVISA13S06' AND a.i_action_seq = '01');

-- POCVISA13S6A
INSERT INTO nap."case" (i_case_id, i_case, c_acq_platform, c_case_ntwk, c_case_src, c_ntwk_case_id, i_acq_refnce_num, c_merchant_name, c_cntry, d_tr, d_prcss_tr, a_purchase_amt, c_purchase_currency, i_purchase_amt_exponent, c_auth_tr, c_mcc, a_csh_back, i_acct_cdh_lst, x_moto, c_entry_mode, x_insrt, z_insrt)
SELECT coalesce(max(i_case_id), 0) + 1, 'POCVISA13S6A', 'NAP', 'VISA', 'SYNTHETIC', '5466030125', '74697695102920050746821', 'www.alie.com', NULL, DATE '2025-04-11', DATE '2025-04-12', 28.94, 'EUR', 2, '410455', '5399', NULL, '1111', NULL, NULL, 'POC_SEED', now() FROM nap."case"
HAVING NOT EXISTS (SELECT 1 FROM nap."case" WHERE i_case = 'POCVISA13S6A');
INSERT INTO nap.action (i_action_id, i_case_id, i_action_seq, i_case, c_reason, c_action_sta, d_action_reported, d_response_due, a_dispute_amt, c_dispute_currency, i_dispute_currency_exponent, x_insrt, z_insrt)
SELECT (SELECT coalesce(max(i_action_id), 0) + 1 FROM nap.action), c.i_case_id, '01', 'POCVISA13S6A', '13.1', 'OPEN', DATE '2025-08-01', NULL, 28.94, 'EUR', 2, 'POC_SEED', now()
FROM nap."case" c WHERE c.i_case = 'POCVISA13S6A'
AND NOT EXISTS (SELECT 1 FROM nap.action a WHERE a.i_case = 'POCVISA13S6A' AND a.i_action_seq = '01');

-- POCVISA13S7A
INSERT INTO nap."case" (i_case_id, i_case, c_acq_platform, c_case_ntwk, c_case_src, c_ntwk_case_id, i_acq_refnce_num, c_merchant_name, c_cntry, d_tr, d_prcss_tr, a_purchase_amt, c_purchase_currency, i_purchase_amt_exponent, c_auth_tr, c_mcc, a_csh_back, i_acct_cdh_lst, x_moto, c_entry_mode, x_insrt, z_insrt)
SELECT coalesce(max(i_case_id), 0) + 1, 'POCVISA13S7A', 'NAP', 'VISA', 'SYNTHETIC', '5466030125', '74697695102920050746821', 'www.alie.com', NULL, DATE '2025-04-11', DATE '2025-04-12', 28.94, 'EUR', 2, '410671', '5399', NULL, '1111', NULL, NULL, 'POC_SEED', now() FROM nap."case"
HAVING NOT EXISTS (SELECT 1 FROM nap."case" WHERE i_case = 'POCVISA13S7A');
INSERT INTO nap.action (i_action_id, i_case_id, i_action_seq, i_case, c_reason, c_action_sta, d_action_reported, d_response_due, a_dispute_amt, c_dispute_currency, i_dispute_currency_exponent, x_insrt, z_insrt)
SELECT (SELECT coalesce(max(i_action_id), 0) + 1 FROM nap.action), c.i_case_id, '01', 'POCVISA13S7A', '13.1', 'OPEN', DATE '2025-08-01', NULL, 28.94, 'EUR', 2, 'POC_SEED', now()
FROM nap."case" c WHERE c.i_case = 'POCVISA13S7A'
AND NOT EXISTS (SELECT 1 FROM nap.action a WHERE a.i_case = 'POCVISA13S7A' AND a.i_action_seq = '01');

COMMIT;

-- Check:
-- SELECT c.i_case, c.c_acq_platform, a.i_action_seq, a.c_reason, a.c_action_sta, a.a_dispute_amt, a.c_dispute_currency
-- FROM nap."case" c JOIN nap.action a ON a.i_case = c.i_case
-- WHERE c.i_case IN ('POCVISA13S03', 'POCVISA13S06', 'POCVISA13S6A', 'POCVISA13S7A');

-- Clean-up, when done:
-- DELETE FROM nap.action WHERE i_case IN ('POCVISA13S03', 'POCVISA13S06', 'POCVISA13S6A', 'POCVISA13S7A') AND x_insrt = 'POC_SEED';
-- DELETE FROM nap."case" WHERE i_case IN ('POCVISA13S03', 'POCVISA13S06', 'POCVISA13S6A', 'POCVISA13S7A') AND x_insrt = 'POC_SEED';

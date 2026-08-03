-- ============================================================================
-- Branch-selecting values still to confirm against the real database.
--
-- The build plan's G* gate says every value that selects a branch must come from the real
-- Swagger / DDL / repo, never be invented. These three are currently assumed in
-- application.yml and marked NEEDS CONFIRMATION there. Run these and send back the results.
--
-- Already confirmed, no query needed:
--   c_reason              dotted code stored verbatim ('10.4', '13.2', '13.7', '13.1')
--   c_case_stage = 'CH1'  first chargeback (StageCode enum, ContestService responseType mapping)
--   c_action_sta = 'OPEN' workable (Status enum, ContestService aborts on CLOSED)
--   c_case_ntwk = 'VISA'  Visa network
--   cardNetwork = '4'     sent to MTR - ContestService NetworkType, confirmed with the reviewer
--                         2026-07-31 in preference to the Swagger's own contradictory examples
--
-- Blocking the two review items that cannot be built without an answer:
--   Q2  -> eligibility P5 is currently NOT enforcing c_migration_sta (review item A3)
--   Q5-Q7 -> reason 13.1's address-consistency check has no address to compare against (A2)
-- ============================================================================

------------------------------------------------------------------------------
-- Q1. c_action_in_out - which value means an INBOUND action?
--     Expect the inbound value to dominate on CHBK actions at stage CH1.
------------------------------------------------------------------------------
SELECT a.c_action_in_out,
       a.c_action_type,
       a.c_case_stage,
       COUNT(*) AS cnt
FROM wdp.action a
JOIN wdp."case" c ON c.i_case_id = a.i_case_id
WHERE c.c_case_ntwk = 'VISA'
  AND a.d_action_reported >= DATE '2026-01-01'
GROUP BY 1, 2, 3
ORDER BY cnt DESC
LIMIT 50;

------------------------------------------------------------------------------
-- Q2. c_migration_sta - what values occur, and which of them are workable?
--     Needed for P5 "migration status per policy". Cross-tabbed against action status so the
--     policy can be read off the data rather than guessed.
------------------------------------------------------------------------------
SELECT COALESCE(a.c_migration_sta, '<NULL>') AS c_migration_sta,
       a.c_action_sta,
       COUNT(*) AS cnt
FROM wdp.action a
JOIN wdp."case" c ON c.i_case_id = a.i_case_id
WHERE c.c_case_ntwk = 'VISA'
  AND a.d_action_reported >= DATE '2026-01-01'
GROUP BY 1, 2
ORDER BY cnt DESC
LIMIT 50;

------------------------------------------------------------------------------
-- Q3. c_timeframe_expired_ind - which value means expired?
--     The expired value should correlate with a d_response_due already in the past.
------------------------------------------------------------------------------
SELECT COALESCE(a.c_timeframe_expired_ind, '<NULL>') AS c_timeframe_expired_ind,
       COUNT(*)                                       AS cnt,
       COUNT(*) FILTER (WHERE a.d_response_due < CURRENT_DATE) AS response_due_in_past,
       COUNT(*) FILTER (WHERE a.d_response_due >= CURRENT_DATE) AS response_due_in_future
FROM wdp.action a
JOIN wdp."case" c ON c.i_case_id = a.i_case_id
WHERE c.c_case_ntwk = 'VISA'
  AND a.d_action_reported >= DATE '2026-01-01'
GROUP BY 1
ORDER BY cnt DESC;

------------------------------------------------------------------------------
-- Q4. A real 13.6 first-chargeback row, to sanity-check the whole seeded shape at once.
--     Confirms the combination the POC assumes actually occurs in production.
------------------------------------------------------------------------------
SELECT a.c_reason,
       a.c_case_stage,
       a.c_action_type,
       a.c_action_in_out,
       a.c_action_sta,
       a.c_reason_category,
       c.c_case_wrkflw_type,
       c.c_case_src,
       c.c_acq_platform,
       COUNT(*) AS cnt
FROM wdp.action a
JOIN wdp."case" c ON c.i_case_id = a.i_case_id
WHERE c.c_case_ntwk = 'VISA'
  AND a.c_reason = '13.6'
  AND a.d_action_reported >= DATE '2026-01-01'
GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9
ORDER BY cnt DESC
LIMIT 50;
------------------------------------------------------------------------------
-- Q5. Where does a cardholder / billing / shipping address live?
--     Reason 13.1's R1 has to check that the carrier delivered to the right address, and the
--     `case` DDL carries only n_city / c_ste / c_cntry - which sit beside c_merchant_name and look
--     like the MERCHANT's location, not the cardholder's. Before wiring an address comparison we
--     need to know whether an address exists anywhere in the schema at all.
--
--     Send back the whole result; a column named nothing like "address" may still hold one.
------------------------------------------------------------------------------
SELECT table_schema,
       table_name,
       column_name,
       data_type
FROM information_schema.columns
WHERE table_schema IN ('wdp', 'nap')
  AND (column_name ~* 'addr|street|city|state|ste$|zip|postal|post_code|cntry|country|recipient'
       OR column_name ~* 'ship|deliver|consign')
ORDER BY table_schema, table_name, ordinal_position;

------------------------------------------------------------------------------
-- Q6. Are the case's n_city / c_ste / c_cntry the merchant's or the cardholder's?
--     If they are the merchant's, they will be near-constant per c_level5_entity (one merchant,
--     one trading address). If they vary widely within a merchant, they are the cardholder's.
--     That single fact decides whether they can be used for the 13.1 address check.
------------------------------------------------------------------------------
SELECT c.c_level5_entity,
       c.c_merchant_name,
       COUNT(*)                                              AS cases,
       COUNT(DISTINCT c.n_city || '|' || c.c_ste || '|' || c.c_cntry) AS distinct_locations
FROM wdp."case" c
WHERE c.c_case_ntwk = 'VISA'
  AND c.d_case_strt >= DATE '2026-01-01'
GROUP BY 1, 2
HAVING COUNT(*) >= 20
ORDER BY cases DESC
LIMIT 30;

------------------------------------------------------------------------------
-- Q7. Does AVS tell us anything usable instead?
--     c_avs_resp records whether the billing address matched at authorization. If it is populated
--     on e-commerce Visa cases, it is a real address signal we already hold - a different check
--     from "the carrier delivered to the cardholder's address", but one that needs no new source.
------------------------------------------------------------------------------
SELECT COALESCE(c.c_avs_resp, '<NULL>') AS c_avs_resp,
       COALESCE(c.c_entry_mode, '<NULL>') AS c_entry_mode,
       COUNT(*) AS cnt
FROM wdp."case" c
JOIN wdp.action a ON a.i_case_id = c.i_case_id
WHERE c.c_case_ntwk = 'VISA'
  AND a.c_reason = '13.1'
  AND a.d_action_reported >= DATE '2026-01-01'
GROUP BY 1, 2
ORDER BY cnt DESC
LIMIT 50;

------------------------------------------------------------------------------
-- Q8. c_card_holdr_auth - can we tell a Visa Secure authenticated transaction apart?
--     Reason 10.4's first and strongest answer in the merchant guide is "the transaction was
--     authenticated with Visa Secure". The guide asks for nothing more than that - no ECI value,
--     no CAVV - so if this column distinguishes authenticated / attempted / not authenticated,
--     10.4-R1 is a one-field rule that needs no gateway integration at all.
--
--     Cross-tabbed against entry mode and reason code: authentication should cluster on
--     e-commerce, and 10.4 disputes on authenticated transactions should be rare.
------------------------------------------------------------------------------
SELECT COALESCE(c.c_card_holdr_auth, '<NULL>') AS c_card_holdr_auth,
       COALESCE(c.c_entry_mode, '<NULL>')      AS c_entry_mode,
       COALESCE(a.c_reason, '<NULL>')          AS c_reason,
       COUNT(*)                                AS cnt
FROM wdp."case" c
JOIN wdp.action a ON a.i_case_id = c.i_case_id
WHERE c.c_case_ntwk = 'VISA'
  AND a.d_action_reported >= DATE '2026-01-01'
GROUP BY 1, 2, 3
ORDER BY cnt DESC
LIMIT 60;

------------------------------------------------------------------------------
-- Q9. c_avs_resp - which values are the AVS "Y" and "M" matches?
--     Compelling Evidence chart item 3 lets a card-absent merchant answer 10.4 with delivery to
--     the address that returned an AVS match of Y or M. That is reachable with data we already
--     hold, so the exact stored values matter.
------------------------------------------------------------------------------
SELECT COALESCE(c.c_avs_resp, '<NULL>') AS c_avs_resp,
       COUNT(*)                          AS cnt
FROM wdp."case" c
WHERE c.c_case_ntwk = 'VISA'
  AND c.d_case_strt >= DATE '2026-01-01'
GROUP BY 1
ORDER BY cnt DESC
LIMIT 40;



SELECT a.c_reason, c.c_case_wrkflw_type, COUNT(*)                                                                                                                                 
  FROM wdp.action a JOIN wdp."case" c ON c.i_case_id = a.i_case_id                                                                                                                  
  WHERE c.c_case_ntwk = 'VISA' AND a.c_reason IN ('10.4','13.6','11.3','12.5')                                                                                                      
  GROUP BY 1, 2 ORDER BY 1, 3 DESC;


SELECT a.c_reason, c.c_case_wrkflw_type, a.c_case_stage, a.c_action_type, COUNT(*) AS cases                                                                                       
    FROM wdp.action a JOIN wdp."case" c ON c.i_case_id = a.i_case_id                                                                                                                
   WHERE c.c_case_ntwk = 'MASTERCARD' AND a.d_action_processed >= DATE '2026-01-01'                                                                                                 
   GROUP BY 1,2,3,4 ORDER BY cases DESC; 

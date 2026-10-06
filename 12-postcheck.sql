-- Office step 12: what the database must say after steps 7-11 (expected values in the comments).
SELECT count(*) AS requirements FROM wdp_ai.requirement WHERE reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1');                       -- 39
SELECT region, count(*) FROM wdp_ai.requirement WHERE reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1') GROUP BY region ORDER BY 1;   -- ALL 34, EUROPE 4, US 1
SELECT code, region, render_order FROM wdp_ai.requirement WHERE code IN ('G23','G25','G26','G27','G30') ORDER BY code; -- G23 EUROPE 24, G25 US 20, G26 EUROPE 21, G27 EUROPE 27, G30 EUROPE 17
SELECT catalogue_version, agency_mccs, agency_mccs_reviewed FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '13.1'; -- 1.5.0, {4722,7922}, {7922}
SELECT count(*) AS fact_keys FROM wdp_ai.fact_key;                                                           -- 24
SELECT count(*) AS unlinked FROM wdp_ai.fact_key fk
 WHERE NOT EXISTS (SELECT 1 FROM wdp_ai.reason_code_fact_key l WHERE l.fact_key_id = fk.id);                 -- 0
SELECT count(*) AS orphan_facts FROM wdp_ai.extracted_fact ef
 WHERE NOT EXISTS (SELECT 1 FROM wdp_ai.fact_key fk WHERE fk.id = ef.fact_key_id);                           -- 0
SELECT region, count(*) FROM wdp_ai.reason_code_fact_key GROUP BY region ORDER BY 1;                         -- ALL 17, EUROPE 7
SELECT count(*) AS time_limits FROM wdp_ai.requirement_time_limit;                                           -- 10
SELECT r.code, t.conditions FROM wdp_ai.requirement_time_limit t JOIN wdp_ai.requirement r ON r.id = t.requirement_id ORDER BY r.code;
-- G22 {expectedDate}, G23 {coveredByScheme}, G25 {mccIsAgency,cancellationKnown,bankruptNotYes}, G26 {mccIsAgency,cancellationKnown,notBondedInsolvency},
-- G27 {coveredByScheme,schemeInsufficientNotYes}, G32 {noExpectedDate,coveredNotYes}, G33 {expectedDate,coveredNotYes},
-- G34 {noExpectedDate,bankruptNotYes}, G36 {cancellationKnown,mccNotAgency,bankruptNotYes}, G42 {bankruptNotYes}
SELECT key FROM wdp_ai.fact_key ORDER BY key;                                                                -- the 24

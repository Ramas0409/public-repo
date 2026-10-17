-- Office step 25, verification: run after 25-register-seed-1004.sql on wdp_ai. Read-only: SELECTs only. Each query is
-- followed by the result the POC's wdp1004 gives at CL-1010 (2026-10-13). If all match, the seed has landed.

-- 1. Per reason code: catalogue, forms, fields, rows, rendered rows, linked keys
SELECT rc.code, rc.catalogue_version,
       (SELECT count(*) FROM wdp_ai.questionnaire_path p WHERE p.reason_code_id = rc.id) AS forms,
       (SELECT count(*) FROM wdp_ai.questionnaire_field f
          JOIN wdp_ai.questionnaire_path p ON p.id = f.questionnaire_path_id WHERE p.reason_code_id = rc.id) AS fields,
       (SELECT count(*) FROM wdp_ai.requirement r WHERE r.reason_code_id = rc.id) AS rows,
       (SELECT count(r.render_order) FROM wdp_ai.requirement r WHERE r.reason_code_id = rc.id) AS rendered,
       (SELECT count(*) FROM wdp_ai.reason_code_fact_key k WHERE k.reason_code_id = rc.id) AS links
FROM wdp_ai.reason_code rc ORDER BY rc.code;
-- Expected:
--   code | catalogue_version | forms | fields | rows | rendered | links
--   10.4 | 1.0.3             |    20 |    140 |   50 |       38 |     6
--   12.5 | 1.3.0             |     4 |     56 |   17 |       12 |     2
--   13.1 | 1.5.0             |    10 |     62 |   39 |       32 |    24
--   13.2 | 1.1.0             |     9 |     62 |   18 |       14 |     1
--   13.3 | 1.1.0             |    10 |     62 |   31 |       25 |     6
--   13.7 | 1.2.0             |    12 |     64 |   25 |       19 |     4

-- 2. Each code's response type
SELECT DISTINCT rc.code, p.response_type
FROM wdp_ai.questionnaire_path p JOIN wdp_ai.reason_code rc ON rc.id = p.reason_code_id ORDER BY 1;
-- Expected: 10.4 | allocprearb; 12.5, 13.1, 13.2, 13.3, 13.7 | representment

-- 3. 10.4's compelling-evidence types under CE, in display order
SELECT rc.code, string_agg(s.code, ', ' ORDER BY s.display_order) AS evidence_types
FROM wdp_ai.questionnaire_path s
JOIN wdp_ai.questionnaire_path p ON p.id = s.parent_path_id AND p.code = 'CE'
JOIN wdp_ai.reason_code rc ON rc.id = p.reason_code_id
GROUP BY rc.code;
-- Expected: 10.4 | CE01, CE02, CE03, CE04, CE05, CE06, CE07, CE08, CE09, CE11, CE12, CE13, CE14, CE16

-- 4. The one DATE_TIME field (needs step 24), and the fact keys
SELECT (SELECT count(*) FROM wdp_ai.questionnaire_field WHERE data_type = 'DATE_TIME') AS date_time_fields,
       (SELECT count(*) FROM wdp_ai.fact_key) AS fact_keys;
-- Expected: 1 | 30  (10.4 defines no key; its six links are 13.1's keys)

-- 5. G28's anchor is whole (79 characters)
SELECT length(anchor) FROM wdp_ai.requirement
 WHERE code = 'G28' AND reason_code_id = (SELECT id FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '10.4');
-- Expected: 79

-- 6. CL-1003: the other transaction's fields of CE04, CE09 and CE13 say whose they are
SELECT p.code, f.name, f.label FROM wdp_ai.questionnaire_field f
JOIN wdp_ai.questionnaire_path p ON p.id = f.questionnaire_path_id
WHERE p.code IN ('CE04', 'CE09', 'CE13') AND f.label LIKE '%Undisputed Transaction %' ORDER BY p.code, f.display_order;
-- Expected 11 rows: CE04 arn/transactionAmount/transactionDate 'Undisputed Transaction ARN/Amount/Date';
--   CE09 arn/transactionAmount/transactionDate/currency 'Undisputed Transaction ARN/Amount/Date/Currency';
--   CE13 the same four, each 'Previous Undisputed Transaction …'

-- 7. CL-1006: CE03's name label, and 10.4's banned phrases end with 'time-limit condition'
SELECT f.label FROM wdp_ai.questionnaire_field f JOIN wdp_ai.questionnaire_path p ON p.id = f.questionnaire_path_id
WHERE p.code = 'CE03' AND f.name = 'compellingEvidence.proofOfDelivery.name';
-- Expected: Delivered To Name
SELECT banned_phrases FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '10.4';
-- Expected: ... scam, dishonest, time-limit condition (the last entry)

-- 8. CL-1010: 10.4's never-mention clause
SELECT never_mention FROM wdp_ai.reason_code WHERE network = 'VISA' AND code = '10.4';
-- Expected: Never mention terms and conditions or refund policies, or use the word "precondition"

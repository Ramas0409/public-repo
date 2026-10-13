-- Office step 20, verification: run after 20-register-seed.sql (the CL-186/CL-187 five-code seed) on wdp_ai.
-- Read-only: SELECTs only. Each query is followed by the result the POC database gives at CL-187 (2026-10-12).
-- If all six match, the seed has landed.

-- 1. Catalogue versions and form counts per reason code
SELECT rc.code, rc.catalogue_version, rc.enabled,
       (SELECT count(*) FROM wdp_ai.questionnaire_path p WHERE p.reason_code_id = rc.id) AS forms,
       (SELECT count(*) FROM wdp_ai.questionnaire_field f
          JOIN wdp_ai.questionnaire_path p ON p.id = f.questionnaire_path_id
         WHERE p.reason_code_id = rc.id) AS fields
FROM wdp_ai.reason_code rc ORDER BY rc.code;
-- Expected (enabled stays as it was before the seed; the seed never writes it):
--   code | catalogue_version | forms | fields
--   12.5 | 1.3.0             |     4 |     56
--   13.1 | 1.5.0             |    10 |     62
--   13.2 | 1.1.0             |     9 |     62
--   13.3 | 1.1.0             |    10 |     62
--   13.7 | 1.2.0             |    12 |     64

-- 2. Each code's sub-forms under ID, in display order
SELECT rc.code, string_agg(s.code, ', ' ORDER BY s.display_order) AS sub_forms
FROM wdp_ai.questionnaire_path s
JOIN wdp_ai.questionnaire_path p ON p.id = s.parent_path_id AND p.code = 'ID'
JOIN wdp_ai.reason_code rc ON rc.id = p.reason_code_id
GROUP BY rc.code ORDER BY rc.code;
-- Expected (12.5 has no row: its ID has no sub-forms):
--   13.1 | IDRC4, IDRC5, IDRC6, IDRC7, IDRC8, IDRC25
--   13.2 | IDRC1, IDRC2, IDRC3, IDRC24, IDRC25
--   13.3 | IDRC11, IDRC12, IDRC13, IDRC14, IDRC24, IDRC25
--   13.7 | IDRC1, IDRC9, IDRC10, IDRC18, IDRC19, IDRC23, IDRC24, IDRC25

-- 3. The rows that select a specific sub-form (anything but IDRC25)
SELECT rc.code, r.code AS row_code, r.response_reason, r.sub_form_code
FROM wdp_ai.requirement r JOIN wdp_ai.reason_code rc ON rc.id = r.reason_code_id
WHERE r.sub_form_code IS NOT NULL AND r.sub_form_code <> 'IDRC25'
ORDER BY rc.code, r.code;
-- Expected, 8 rows:
--   13.1 | G13 | ID | IDRC4
--   13.1 | G14 | ID | IDRC5
--   13.1 | G17 | ID | IDRC6
--   13.3 | G23 | ID | IDRC24
--   13.7 | G16 | ID | IDRC24
--   13.7 | G7  | ID | IDRC23
--   13.7 | G8  | ID | IDRC9
--   13.7 | G9  | ID | IDRC10

-- 4. 12.5 has no sub-form codes left
SELECT count(*) FILTER (WHERE r.sub_form_code IS NOT NULL) AS with_sub_form,
       count(*) FILTER (WHERE r.response_reason = 'ID') AS id_rows
FROM wdp_ai.requirement r JOIN wdp_ai.reason_code rc ON rc.id = r.reason_code_id
WHERE rc.code = '12.5';
-- Expected:
--   with_sub_form | id_rows
--               0 |      14

-- 5. 12.5's ID form: no sub-form field, a required explanation, comments optional
SELECT p.sub_form_field, f.name, f.max_length, f.required_when, f.display_order
FROM wdp_ai.questionnaire_path p
JOIN wdp_ai.reason_code rc ON rc.id = p.reason_code_id AND rc.code = '12.5'
JOIN wdp_ai.questionnaire_field f ON f.questionnaire_path_id = p.id
WHERE p.code = 'ID' AND f.display_order >= 11 ORDER BY f.display_order;
-- Expected (sub_form_field NULL on every row):
--   sub_form_field | name        | max_length | required_when | display_order
--   (null)         | explanation |      10000 | ALWAYS        |            11
--   (null)         | comments    |       5000 | NEVER         |            12
--   (null)         | userId      |     (null) | ALWAYS        |            13

-- 6. 13.2's IDRC3: the explanation and the requested cancellation date
SELECT s.code, f.name, f.data_type, f.required_when
FROM wdp_ai.questionnaire_field f
JOIN wdp_ai.questionnaire_path s ON s.id = f.questionnaire_path_id
JOIN wdp_ai.reason_code rc ON rc.id = s.reason_code_id AND rc.code = '13.2'
WHERE s.code = 'IDRC3' ORDER BY f.display_order;
-- Expected:
--   IDRC3 | explanation                                        | LONG_TEXT | ALWAYS
--   IDRC3 | invalidDispute.cardholderRequestedCancellationdate | DATE      | ALWAYS

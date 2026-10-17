-- Office step 24 (branch 1004-code; run only when 1004-code is merged into what the office runs): questionnaire_field
-- admits DATE_TIME, the allocprearb page's MM/dd/yyyy-HH:mm (POC db/1004/V1001__questionnaire_date_time.sql,
-- CL-1001). Nothing else in the schema moves. Run BEFORE step 25, whose 10.4 rows carry a DATE_TIME field.
-- Safe to run again.

BEGIN;

ALTER TABLE wdp_ai.questionnaire_field DROP CONSTRAINT IF EXISTS questionnaire_field_type_check;
ALTER TABLE wdp_ai.questionnaire_field ADD CONSTRAINT questionnaire_field_type_check
    CHECK (data_type IN ('TEXT', 'LONG_TEXT', 'AMOUNT', 'DATE', 'DATE_TIME', 'CODE', 'BOOLEAN', 'SYSTEM'));

COMMIT;

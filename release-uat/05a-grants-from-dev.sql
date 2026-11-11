-- UAT/prod step 5, part A: RUN IN DEV (read-only). Writes out, as GRANT statements, every grant dev's service users
-- hold on the auto-contest tables and the two schemas, so UAT and prod get exactly dev's access. Copy the result
-- column, replace each dev user name with that environment's user, and run it there as the owner (step 5).
-- Grants to the owner itself are left out (the owner has every right).

WITH t AS (
    SELECT c.oid, c.relowner, c.relacl, n.nspname, c.relname
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind = 'r'
      AND ((n.nspname = 'wdp_ai' AND c.relname IN (
              'reason_code', 'requirement', 'requirement_time_limit', 'requirement_record_rule', 'fact_key',
              'reason_code_fact_key', 'questionnaire_path', 'questionnaire_field', 'questionnaire_option',
              'merchant_document_type', 'case_document', 'document_text', 'document_token_map', 'extracted_fact',
              'classifier_run', 'contest_comparison', 'qa_ops_questionnaire', 'model_call_usage', 'report_user'))
        OR (n.nspname = 'wdp' AND c.relname IN ('ai_response_events', 'bre_orchestration_outbox', 'case', 'action')))
)
SELECT grant_statement FROM (
SELECT format('GRANT USAGE ON SCHEMA %I TO %I;', n.nspname, r.rolname) AS grant_statement
FROM pg_namespace n CROSS JOIN LATERAL aclexplode(n.nspacl) a JOIN pg_roles r ON r.oid = a.grantee
WHERE n.nspname IN ('wdp_ai', 'wdp') AND a.privilege_type = 'USAGE' AND a.grantee <> n.nspowner
UNION ALL
SELECT format('GRANT %s ON %I.%I TO %I;', string_agg(a.privilege_type, ', ' ORDER BY a.privilege_type), t.nspname, t.relname, r.rolname)
FROM t CROSS JOIN LATERAL aclexplode(t.relacl) a JOIN pg_roles r ON r.oid = a.grantee
WHERE a.grantee <> t.relowner
GROUP BY t.nspname, t.relname, r.rolname
) g
ORDER BY grant_statement COLLATE "C";

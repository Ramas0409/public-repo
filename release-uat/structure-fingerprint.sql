-- The auto-contest tables' structure fingerprint: one row per table, one md5 each for its columns, its constraints and
-- its indexes. Run it in each environment (POC, dev, UAT, prod) and compare line by line with the expected rows in
-- README.md: a row that differs is a table whose structure has drifted. Read-only.
--
-- Run both statements together (pgAdmin shows the last result). The first fixes the search path, so the definitions
-- print the same way in every session. Columns are sorted by name, not position, so a column added in a different
-- order does not count as drift; constraint and index names are left out, only their definitions count. A CHECK is
-- compared without its casts and brackets: PostgreSQL prints the same IN (...) rule differently once a table has been
-- through pg_dump, and UAT and prod are built from a dump while dev was built from the migrations.
-- To see what differs on a row, run the detail query at the bottom in both environments and compare.

SET search_path TO pg_catalog;

WITH t AS (
    SELECT c.oid, n.nspname || '.' || c.relname AS tbl
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind = 'r'
      AND ((n.nspname = 'wdp_ai' AND c.relname IN (
              'reason_code', 'requirement', 'requirement_time_limit', 'requirement_record_rule', 'fact_key',
              'reason_code_fact_key', 'questionnaire_path', 'questionnaire_field', 'questionnaire_option',
              'merchant_document_type', 'case_document', 'document_text', 'document_token_map', 'extracted_fact',
              'classifier_run', 'contest_comparison', 'qa_ops_questionnaire', 'model_call_usage', 'report_user'))
        OR (n.nspname = 'wdp' AND c.relname = 'ai_response_events'))
),
cols AS (
    SELECT t.tbl, count(*) AS n,
           md5(string_agg(a.attname || '|' || format_type(a.atttypid, a.atttypmod) || '|' || a.attnotnull || '|'
                          || coalesce(pg_get_expr(d.adbin, d.adrelid), '') || '|' || a.attidentity::text || '|' || a.attstorage::text,
                          '#' ORDER BY a.attname COLLATE "C")) AS h
    FROM t JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    GROUP BY t.tbl
),
con AS (
    SELECT t.tbl,
           x.contype::text || '|' || CASE WHEN x.contype = 'c'
               THEN regexp_replace(regexp_replace(pg_get_constraintdef(x.oid),
                        '::(text\[\]|text|character varying|bpchar|numeric|integer|bigint)', '', 'g'), '[()]', '', 'g')
               ELSE pg_get_constraintdef(x.oid) END AS def
    FROM t JOIN pg_constraint x ON x.conrelid = t.oid
),
cons AS (
    SELECT tbl, count(*) AS n, md5(string_agg(def, '#' ORDER BY def COLLATE "C")) AS h
    FROM con GROUP BY tbl
),
idx AS (
    SELECT t.tbl, count(*) AS n,
           md5(string_agg(regexp_replace(pg_get_indexdef(i.indexrelid), '^(CREATE (UNIQUE )?INDEX )\S+ ', '\1'), '#'
                          ORDER BY regexp_replace(pg_get_indexdef(i.indexrelid), '^(CREATE (UNIQUE )?INDEX )\S+ ', '\1') COLLATE "C")) AS h
    FROM t JOIN pg_index i ON i.indrelid = t.oid
    WHERE NOT EXISTS (SELECT 1 FROM pg_constraint x WHERE x.conindid = i.indexrelid AND x.conrelid = t.oid)
    GROUP BY t.tbl
)
SELECT t.tbl AS table_name,
       cols.n AS columns, cols.h AS columns_md5,
       coalesce(cons.n, 0) AS constraints, coalesce(cons.h, '-') AS constraints_md5,
       coalesce(idx.n, 0) AS indexes, coalesce(idx.h, '-') AS indexes_md5
FROM t JOIN cols USING (tbl)
LEFT JOIN cons USING (tbl)
LEFT JOIN idx USING (tbl)
ORDER BY t.tbl COLLATE "C";

-- Detail (run alone, after the SET above, to find what differs on a table):
--
-- SELECT 'column' AS part, a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
--        || CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END
--        || coalesce(' DEFAULT ' || pg_get_expr(d.adbin, d.adrelid), '')
--        || CASE a.attidentity::text WHEN 'a' THEN ' IDENTITY ALWAYS' WHEN 'd' THEN ' IDENTITY BY DEFAULT' ELSE '' END
--        || ' storage=' || a.attstorage::text AS definition
-- FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
-- WHERE a.attrelid = 'wdp_ai.classifier_run'::regclass AND a.attnum > 0 AND NOT a.attisdropped
-- UNION ALL
-- SELECT 'constraint', x.contype::text || ' ' || pg_get_constraintdef(x.oid)
-- FROM pg_constraint x WHERE x.conrelid = 'wdp_ai.classifier_run'::regclass
-- UNION ALL
-- SELECT 'index', pg_get_indexdef(i.indexrelid)
-- FROM pg_index i WHERE i.indrelid = 'wdp_ai.classifier_run'::regclass
-- ORDER BY 1, 2;

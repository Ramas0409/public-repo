-- The register's fingerprint: one row per reason code, one md5 per part of the register. Run it before and after
-- any change to a register table (the seed, a migration, a hand fix), here and in the office, and compare line by
-- line: a code that should not move keeps its hashes, and a code that should move takes the POC's.
--
-- Read-only. Identities (id, reason_code_id, requirement_id, ...) differ between databases, so every row is hashed
-- by its natural keys; reason_code.enabled is left out, since which codes run is each environment's own choice.
-- The questionnaire options with no reason code (shared by every code) are the row 'ALL'. Every sort is COLLATE "C"
-- (byte order), so the hashes do not depend on the database's collation: the POC's Windows collation and the
-- office's order underscores differently.

WITH rc AS (
    SELECT id, network, code FROM wdp_ai.reason_code
),
reason AS (
    SELECT r.id, md5((to_jsonb(r) - 'id' - 'enabled')::text) AS h FROM wdp_ai.reason_code r
),
requirements AS (
    SELECT q.reason_code_id AS id,
           md5(string_agg((to_jsonb(q) - 'id' - 'reason_code_id')::text, '#' ORDER BY q.code COLLATE "C")) AS h
    FROM wdp_ai.requirement q GROUP BY q.reason_code_id
),
time_limits AS (
    SELECT q.reason_code_id AS id,
           md5(string_agg(q.code || '|' || coalesce(f.key, '') || '|'
                          || (to_jsonb(t) - 'id' - 'requirement_id' - 'day_count_fact_key_id')::text,
                          '#' ORDER BY q.code COLLATE "C")) AS h
    FROM wdp_ai.requirement_time_limit t
    JOIN wdp_ai.requirement q ON q.id = t.requirement_id
    LEFT JOIN wdp_ai.fact_key f ON f.id = t.day_count_fact_key_id
    GROUP BY q.reason_code_id
),
record_rules AS (
    SELECT q.reason_code_id AS id,
           md5(string_agg(q.code || '|' || (to_jsonb(x) - 'id' - 'requirement_id')::text, '#' ORDER BY q.code COLLATE "C")) AS h
    FROM wdp_ai.requirement_record_rule x
    JOIN wdp_ai.requirement q ON q.id = x.requirement_id
    GROUP BY q.reason_code_id
),
fact_keys AS (
    SELECT l.reason_code_id AS id,
           md5(string_agg(l.region || '|' || (to_jsonb(f) - 'id')::text, '#' ORDER BY f.key COLLATE "C")) AS h
    FROM wdp_ai.reason_code_fact_key l
    JOIN wdp_ai.fact_key f ON f.id = l.fact_key_id
    GROUP BY l.reason_code_id
),
paths AS (
    SELECT p.reason_code_id AS id,
           md5(string_agg(coalesce(parent.code, '') || '|'
                          || (to_jsonb(p) - 'id' - 'reason_code_id' - 'parent_path_id')::text,
                          '#' ORDER BY p.response_type COLLATE "C", coalesce(parent.code, '') COLLATE "C", p.code COLLATE "C")) AS h
    FROM wdp_ai.questionnaire_path p
    LEFT JOIN wdp_ai.questionnaire_path parent ON parent.id = p.parent_path_id
    GROUP BY p.reason_code_id
),
fields AS (
    SELECT p.reason_code_id AS id,
           md5(string_agg(p.response_type || '|' || p.code || '|'
                          || (to_jsonb(d) - 'id' - 'questionnaire_path_id')::text,
                          '#' ORDER BY p.response_type COLLATE "C", p.code COLLATE "C", d.name COLLATE "C")) AS h
    FROM wdp_ai.questionnaire_field d
    JOIN wdp_ai.questionnaire_path p ON p.id = d.questionnaire_path_id
    GROUP BY p.reason_code_id
),
options AS (
    SELECT o.reason_code_id AS id,
           md5(string_agg((to_jsonb(o) - 'id' - 'reason_code_id')::text, '#'
                          ORDER BY o.network COLLATE "C", o.field_name COLLATE "C", o.code COLLATE "C")) AS h
    FROM wdp_ai.questionnaire_option o
    GROUP BY o.reason_code_id
),
documents AS (
    SELECT t.reason_code_id AS id,
           md5(string_agg((to_jsonb(t) - 'id' - 'reason_code_id')::text, '#' ORDER BY t.key COLLATE "C")) AS h
    FROM wdp_ai.merchant_document_type t GROUP BY t.reason_code_id
)
SELECT rc.network || ' ' || rc.code AS reason_code,
       left(reason.h, 8)       AS reason_code_row,
       left(requirements.h, 8) AS requirements,
       left(time_limits.h, 8)  AS time_limits,
       left(record_rules.h, 8) AS record_rules,
       left(fact_keys.h, 8)    AS fact_keys,
       left(paths.h, 8)        AS q_paths,
       left(fields.h, 8)       AS q_fields,
       left(options.h, 8)      AS q_options,
       left(documents.h, 8)    AS documents
FROM rc
JOIN reason ON reason.id = rc.id
LEFT JOIN requirements ON requirements.id = rc.id
LEFT JOIN time_limits ON time_limits.id = rc.id
LEFT JOIN record_rules ON record_rules.id = rc.id
LEFT JOIN fact_keys ON fact_keys.id = rc.id
LEFT JOIN paths ON paths.id = rc.id
LEFT JOIN fields ON fields.id = rc.id
LEFT JOIN options ON options.id = rc.id
LEFT JOIN documents ON documents.id = rc.id
UNION ALL
SELECT 'ALL', NULL, NULL, NULL, NULL, NULL, NULL, NULL, left(options.h, 8), NULL
FROM options WHERE options.id IS NULL
ORDER BY 1;

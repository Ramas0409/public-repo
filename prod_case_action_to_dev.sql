-- Run in PRODUCTION (read only: one SELECT). Writes the INSERT statements that copy the wanted cases into the DEV
-- database's nap.case and nap.action tables: every column of the case row and of its action '01' (the action the
-- auto-response event service reads), as production holds them, but for three values set for dev:
--   - i_case_id and i_action_id: dev's max + 1, so no id collides with dev's own rows (neither key has a default);
--   - the action's i_case_id: the dev case row's id;
--   - the action's c_action_sta: 'OPEN', since the event service skips an action that is not open, and a production
--     case being copied for a test is usually closed.
-- Each statement is a no-op where dev already has the case (by i_case) or the action (by i_case, i_action_seq).
--
-- Each row travels as hex (its JSON, UTF-8, hex-encoded), so the output has no double quote and nothing in it is
-- changed by a CSV export. How, in pgAdmin:
--   1. File > Preferences > Query Tool > CSV/TXT Output: CSV quoting "None" (once).
--   2. Put the case numbers in "wanted", run, and save the result to a file (Data Output, the download button). The
--      header line is a SQL comment; keep the rows in the order they come (every case before its action).
--   3. Open that file in DEV's Query Tool and run it as it is.
-- The output holds card and customer data from production: keep it within the office.
WITH wanted(i_case) AS (
    VALUES ('CASE_NUMBER_1'),
           ('CASE_NUMBER_2')
),
statements AS (
    SELECT 0 AS step, '' AS i_case, 'BEGIN;' AS statement
    UNION ALL
    SELECT 1, c.i_case, format(
        'INSERT INTO nap.case SELECT (jsonb_populate_record(NULL::nap.case,'
        ' convert_from(decode(%L, ''hex''), ''UTF8'')::jsonb'
        ' || jsonb_build_object(''i_case_id'', (SELECT coalesce(max(i_case_id), 0) + 1 FROM nap.case)))).*'
        ' WHERE NOT EXISTS (SELECT 1 FROM nap.case WHERE i_case = %L);',
        encode(convert_to(to_jsonb(c)::text, 'UTF8'), 'hex'), c.i_case)
    FROM wanted w
    JOIN nap.case c ON c.i_case = w.i_case
    UNION ALL
    SELECT 2, a.i_case, format(
        'INSERT INTO nap.action SELECT (jsonb_populate_record(NULL::nap.action,'
        ' convert_from(decode(%L, ''hex''), ''UTF8'')::jsonb'
        ' || jsonb_build_object(''i_action_id'', (SELECT coalesce(max(i_action_id), 0) + 1 FROM nap.action),'
        ' ''i_case_id'', (SELECT i_case_id FROM nap.case WHERE i_case = %L), ''c_action_sta'', ''OPEN''))).*'
        ' WHERE NOT EXISTS (SELECT 1 FROM nap.action WHERE i_case = %L AND i_action_seq = %L);',
        encode(convert_to(to_jsonb(a)::text, 'UTF8'), 'hex'), a.i_case, a.i_case, a.i_action_seq)
    FROM wanted w
    JOIN nap.action a ON a.i_case = w.i_case AND a.i_action_seq = '01'
    UNION ALL
    SELECT 3, '', 'COMMIT;'
)
SELECT statement AS "-- run this file in DEV"
FROM statements
ORDER BY step, i_case;

-- The wanted cases production has no case row or no action '01' for (none of their statements are written):
-- WITH wanted(i_case) AS (VALUES ('CASE_NUMBER_1'), ('CASE_NUMBER_2'))
-- SELECT w.i_case FROM wanted w
-- WHERE NOT EXISTS (SELECT 1 FROM nap.case c JOIN nap.action a ON a.i_case = c.i_case AND a.i_action_seq = '01'
--                   WHERE c.i_case = w.i_case);

-- Run in PRODUCTION (read only). Writes the INSERT statements that copy the wanted cases into the DEV database's
-- wdp."case" and wdp.action tables: every column of the case row and of its action '01' (the action the auto-response
-- event service reads), as production holds them, but for three values set for dev:
--   - i_case_id and i_action_id: dev's max + 1, so no id collides with dev's own rows (neither key has a default);
--   - the action's i_case_id: the dev case row's id;
--   - the action's c_action_sta: 'OPEN', since the event service skips an action that is not open, and a production
--     case being copied for a test is usually closed.
-- Each statement is a no-op where dev already has the case (by i_case) or the action (by i_case, i_action_seq).
--
-- How: put the case numbers in "wanted", run, and copy the one output column (statement order matters: every case
-- before its action, so keep the output sorted as it comes) into a file; run that file in DEV (psql -f, or the query
-- tool). The output holds card and customer data from production: keep it within the office.
WITH wanted(i_case) AS (
    VALUES ('CASE_NUMBER_1'),
           ('CASE_NUMBER_2')
),
statements AS (
    SELECT 0 AS step, '' AS i_case, 'BEGIN;' AS statement
    UNION ALL
    SELECT 1, c.i_case, format(
        'INSERT INTO wdp."case" SELECT (json_populate_record(NULL::wdp."case", (%L::jsonb'
        ' || jsonb_build_object(''i_case_id'', (SELECT coalesce(max(i_case_id), 0) + 1 FROM wdp."case")))::json)).*'
        ' WHERE NOT EXISTS (SELECT 1 FROM wdp."case" WHERE i_case = %L);',
        to_jsonb(c)::text, c.i_case)
    FROM wanted w
    JOIN wdp."case" c ON c.i_case = w.i_case
    UNION ALL
    SELECT 2, a.i_case, format(
        'INSERT INTO wdp.action SELECT (json_populate_record(NULL::wdp.action, (%L::jsonb'
        ' || jsonb_build_object(''i_action_id'', (SELECT coalesce(max(i_action_id), 0) + 1 FROM wdp.action),'
        ' ''i_case_id'', (SELECT i_case_id FROM wdp."case" WHERE i_case = %L), ''c_action_sta'', ''OPEN''))::json)).*'
        ' WHERE NOT EXISTS (SELECT 1 FROM wdp.action WHERE i_case = %L AND i_action_seq = %L);',
        to_jsonb(a)::text, a.i_case, a.i_case, a.i_action_seq)
    FROM wanted w
    JOIN wdp.action a ON a.i_case = w.i_case AND a.i_action_seq = '01'
    UNION ALL
    SELECT 3, '', 'COMMIT;'
)
SELECT statement
FROM statements
ORDER BY step, i_case;

-- The wanted cases production has no case row or no action '01' for (none of their statements are written):
-- WITH wanted(i_case) AS (VALUES ('CASE_NUMBER_1'), ('CASE_NUMBER_2'))
-- SELECT w.i_case FROM wanted w
-- WHERE NOT EXISTS (SELECT 1 FROM wdp."case" c JOIN wdp.action a ON a.i_case = c.i_case AND a.i_action_seq = '01'
--                   WHERE c.i_case = w.i_case);

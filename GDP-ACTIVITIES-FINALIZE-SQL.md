# GDP-ACTIVITIES-FINALIZE-SQL.md
**SQL pack to finalise the activities-search analysis: production inventory (B1), MID sizing (B2) and baseline plans for today's queries (B3)**
*Prepared 2026-11-11. Nothing in this file has been run. Companion: `docs/GDP-ACTIVITIES-DATE-COLUMN-ANALYSIS.md` (Part A), cited below as "Part A".*

Path prefixes are the same as Part A: `C27/` = `gcp-case-search-service/main/java/com/wp/gcp/casesearch/`, `J/` = `gcp-chargeback-service/src/main/java/com/wp/gcp/chargeback/`.

---

## 0. How to run

**Safety.** Every block is read-only: `SELECT`, `EXPLAIN` on a `SELECT`, or a catalog read. There is no DDL and no DML. Each block opens a read-only transaction and sets its own timeout:
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '…';   -- per block
SET LOCAL lock_timeout = '5s';
```
After each block, run `ROLLBACK;`. If a block errors or times out, run `ROLLBACK;` before the next one.

**Where to run.** Use read replicas, as you planned.
- On a replica, a long query can be cancelled with `canceling statement due to conflict with recovery`. If that happens, re-run it. B1.0 records `max_standby_streaming_delay` and `hot_standby_feedback` so the cause is visible.
- **Replica-local numbers.** Usage counters (`seq_scan`, `idx_scan`, `idx_tup_*`), `n_live_tup` / `n_dead_tup` and the vacuum/analyze times in B1.1–B1.2 describe **the instance you run on**.
  - Vacuum runs only on the writer, so a replica may show NULL or stale vacuum times.
  - `pg_class` sizes, `reltuples`, `relallvisible` and `pg_stats` come from the catalog, which Aurora replicas share with the writer.
  - If you are allowed to run B1.1 and B1.2 on the writer as well, they are catalog reads and very cheap; save that output as a second section in the same file. Otherwise note "replica only" in the file.

**pgAdmin.** pgAdmin shows only the last statement's result, so each sub-block holds a single query. Paste one sub-block, press F5, copy the whole Data Output grid with headers (or use *Save results to file*), append it to the named result file under a heading such as `### B1.2`, then run `ROLLBACK;`.

**Result files** go in `docs/results/`. Create the folder if it does not exist. Do not commit or stage anything in it.

**Timing classes:** *light* is under 5 s; *medium* is under 2 min; *heavy* is minutes, so run it off-peak. These are estimates from earlier runs on US-prod (2026-11-10: action-window scans 7–9 s, level-4 joins 12–132 s, per-consumer scope about 90 s).

---

## B1. Production index and table inventory

Run **every** B1 sub-block on **US** (it reports schema `wdp`) and on **UK** (it reports schema `nap`). The text is identical for both, because each query filters `nspname IN ('wdp','nap')`.
- **Result files:** `results/b1-us-indexes.txt` and `results/b1-uk-indexes.txt`. All B1 sub-blocks, plus B3.0-check, go into the same file for that region.
- **Run time class:** light (catalog only).
- B1.7 reads `pg_stats`, and B1.8 reads `pg_stat_statements` if it exists.

### B1.0 Server, settings and stats-reset time
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT name, setting, unit, source
FROM pg_settings
WHERE name IN ('server_version','TimeZone','DateStyle','random_page_cost','seq_page_cost',
               'effective_cache_size','work_mem','shared_buffers','default_statistics_target',
               'jit','plan_cache_mode','max_parallel_workers_per_gather','track_io_timing',
               'hot_standby_feedback','max_standby_streaming_delay')
UNION ALL SELECT 'pg_is_in_recovery (t = replica)', pg_is_in_recovery()::text, NULL, NULL
UNION ALL SELECT 'stats_reset (this database)',
                 (SELECT stats_reset::text FROM pg_stat_database WHERE datname = current_database()), NULL, NULL
UNION ALL SELECT 'now()', now()::text, NULL, NULL
UNION ALL SELECT 'current_date (session TZ)', current_date::text, NULL, NULL
ORDER BY 1;
```

### B1.1 Tables: size, estimated rows, vacuum/analyze, all-visible share
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT n.nspname                                   AS schema,
       c.relname                                   AS table_name,
       c.reltuples::bigint                         AS est_rows_pg_class,
       c.relpages,
       c.relallvisible,
       round(100.0 * c.relallvisible / nullif(c.relpages, 0), 1) AS pct_all_visible,
       pg_size_pretty(pg_table_size(c.oid))        AS table_size,        -- heap + TOAST + FSM/VM
       pg_size_pretty(pg_indexes_size(c.oid))      AS all_indexes_size,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
       pg_total_relation_size(c.oid)               AS total_bytes,
       s.n_live_tup, s.n_dead_tup, s.n_mod_since_analyze,
       s.seq_scan, s.seq_tup_read, s.idx_scan, s.idx_tup_fetch,
       s.last_vacuum, s.last_autovacuum, s.last_analyze, s.last_autoanalyze,
       s.vacuum_count, s.autovacuum_count, s.analyze_count, s.autoanalyze_count
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
WHERE n.nspname IN ('wdp','nap')
  AND c.relname IN ('case','action','case_lock')
  AND c.relkind IN ('r','p')
ORDER BY 1, 2;
```

### B1.2 Indexes: definition, unique/primary/partial, key vs INCLUDE columns, valid flag, size, usage
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT n.nspname                         AS schema,
       t.relname                         AS table_name,
       i.relname                         AS index_name,
       am.amname                         AS method,
       ix.indisprimary                   AS is_primary,
       ix.indisunique                    AS is_unique,
       (ix.indpred IS NOT NULL)          AS is_partial,
       pg_get_expr(ix.indpred, ix.indrelid) AS partial_predicate,
       ix.indisvalid                     AS is_valid,
       ix.indisready                     AS is_ready,
       ARRAY(SELECT pg_get_indexdef(ix.indexrelid, k, true)
             FROM generate_series(1, ix.indnkeyatts) k)                AS key_columns,
       ARRAY(SELECT pg_get_indexdef(ix.indexrelid, k, true)
             FROM generate_series(ix.indnkeyatts + 1, ix.indnatts) k)  AS include_columns,
       pg_size_pretty(pg_relation_size(i.oid)) AS index_size,
       pg_relation_size(i.oid)           AS index_bytes,
       s.idx_scan, s.idx_tup_read, s.idx_tup_fetch,
       pg_get_indexdef(i.oid)            AS indexdef
FROM pg_index ix
JOIN pg_class i      ON i.oid = ix.indexrelid
JOIN pg_class t      ON t.oid = ix.indrelid
JOIN pg_namespace n  ON n.oid = t.relnamespace
JOIN pg_am am        ON am.oid = i.relam
LEFT JOIN pg_stat_user_indexes s ON s.indexrelid = i.oid
WHERE n.nspname IN ('wdp','nap')
  AND t.relname IN ('case','action','case_lock')
ORDER BY 1, 2, 3;
```

### B1.2b (load only; added 2026-11-12): activity-date coverage by month
Load is a partial, older copy of prod (load B1.1: about half of prod's rows; US unchanged since early October). This block shows which months load actually holds, so the picker date ranges can be anchored on load's last data day.
- Both schemas are in the same load database, so **run it once**.
- It returns counts and dates only, so the output can be saved as is: `docs/results-phase1/load/l1-load-date-coverage.txt`.

**Run time class:** medium. It scans both `action` tables: US `wdp.action` is 7.7 GB and UK `nap.action` 3.0 GB in load.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15min';
SET LOCAL lock_timeout = '5s';

SELECT 'wdp' AS schema, date_trunc('month', d_action_processed)::date AS month,
       count(*) AS actions, count(d_action_processed_est) AS with_est,
       max(d_action_processed) AS max_processed, max(d_action_processed_est) AS max_est, max(z_insrt) AS max_insert
FROM wdp.action
WHERE d_action_processed >= DATE '2026-05-01'
GROUP BY 1, 2
UNION ALL
SELECT 'nap', date_trunc('month', d_action_processed)::date,
       count(*), NULL, max(d_action_processed), NULL, max(z_insrt)
FROM nap.action
WHERE d_action_processed >= DATE '2026-05-01'
GROUP BY 1, 2
ORDER BY 1, 2;
```
Then run `ROLLBACK;`.

### B1.2c (load only; added 2026-11-12): UK activity-date coverage, full history
B1.2b in load found only 2 UK actions since 2026-05-01 (the last on 2026-06-12), so the UK window has to be placed inside the months load actually holds. This block returns monthly counts for the whole of `nap.action`, plus the null count. Counts and dates only, so it can be saved: `docs/results-phase1/load/l1-load-date-coverage.txt`, under `### B1.2c`. **Run time class:** medium; it scans `nap.action`, 3.0 GB in load.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15min';
SET LOCAL lock_timeout = '5s';

SELECT date_trunc('month', d_action_processed)::date AS month,
       count(*)                AS actions,
       min(d_action_processed) AS min_processed,
       max(d_action_processed) AS max_processed,
       max(z_insrt)            AS max_insert
FROM nap.action
GROUP BY 1
ORDER BY 1;
```
Then run `ROLLBACK;`. The NULL month row, if any, counts actions with no processed date.

### B1.3 Constraints (PK, unique, FK, check), including FKs from other tables that point at these
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT n.nspname              AS schema,
       t.relname              AS table_name,
       con.conname            AS constraint_name,
       CASE con.contype WHEN 'p' THEN 'PRIMARY KEY' WHEN 'u' THEN 'UNIQUE' WHEN 'f' THEN 'FOREIGN KEY'
                        WHEN 'c' THEN 'CHECK' WHEN 'x' THEN 'EXCLUDE' ELSE con.contype::text END AS type,
       con.convalidated       AS validated,
       con.confrelid::regclass AS references_table,
       pg_get_constraintdef(con.oid, true) AS definition
FROM pg_constraint con
JOIN pg_class t     ON t.oid = con.conrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE (n.nspname IN ('wdp','nap') AND t.relname IN ('case','action','case_lock'))
   OR con.confrelid IN (SELECT c2.oid FROM pg_class c2 JOIN pg_namespace n2 ON n2.oid = c2.relnamespace
                        WHERE n2.nspname IN ('wdp','nap') AND c2.relname IN ('case','action','case_lock'))
ORDER BY 1, 2, 4, 3;
```

### B1.4 Triggers on `case` and `action`
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT n.nspname        AS schema,
       t.relname        AS table_name,
       tg.tgname        AS trigger_name,
       CASE tg.tgenabled WHEN 'O' THEN 'enabled' WHEN 'D' THEN 'disabled'
                         WHEN 'R' THEN 'replica-only' WHEN 'A' THEN 'always' END AS enabled,
       CASE WHEN tg.tgtype & 2  = 2  THEN 'BEFORE'
            WHEN tg.tgtype & 64 = 64 THEN 'INSTEAD OF' ELSE 'AFTER' END        AS timing,
       concat_ws(' OR ',
                 CASE WHEN tg.tgtype & 4  = 4  THEN 'INSERT'   END,
                 CASE WHEN tg.tgtype & 16 = 16 THEN 'UPDATE'   END,
                 CASE WHEN tg.tgtype & 8  = 8  THEN 'DELETE'   END,
                 CASE WHEN tg.tgtype & 32 = 32 THEN 'TRUNCATE' END)            AS events,
       CASE WHEN tg.tgtype & 1 = 1 THEN 'ROW' ELSE 'STATEMENT' END            AS level,
       tg.tgfoid::regprocedure AS function_name,
       pg_get_triggerdef(tg.oid, true) AS trigger_def
FROM pg_trigger tg
JOIN pg_class t     ON t.oid = tg.tgrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE NOT tg.tgisinternal
  AND n.nspname IN ('wdp','nap')
  AND t.relname IN ('case','action')
ORDER BY 1, 2, 3;
```

### B1.5 Source of any function that touches `d_action_processed_est`
This covers trigger functions on these tables first, then any other function in `wdp`/`nap` whose body mentions the column, such as a writer procedure.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT DISTINCT
       p.oid::regprocedure                         AS function_name,
       l.lanname                                   AS language,
       EXISTS (SELECT 1 FROM pg_trigger tg WHERE tg.tgfoid = p.oid AND NOT tg.tgisinternal) AS used_by_trigger,
       pg_get_functiondef(p.oid)                   AS function_source
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
JOIN pg_language l  ON l.oid = p.prolang
WHERE p.prokind IN ('f','p')
  AND p.prosrc ILIKE '%d_action_processed_est%'
  AND (n.nspname IN ('wdp','nap')
       OR p.oid IN (SELECT tg.tgfoid FROM pg_trigger tg JOIN pg_class t ON t.oid = tg.tgrelid
                    JOIN pg_namespace n2 ON n2.oid = t.relnamespace
                    WHERE n2.nspname IN ('wdp','nap') AND t.relname IN ('case','action')))
ORDER BY 1;
```
On UK this is expected to return no rows. Record "0 rows" in that case.

### B1.5b (US only; added 2026-11-11): full body of `wdp.set_d_action_processed_est()`
**Why:** the B1.5 grid showed only the first line of the function. This query returns one row per line of its source.
- **Run time class:** instant (catalog only).
- **Result file:** export to `images/B1.5b-us.csv`; it is appended to `results/b1-us-indexes.txt` under `### B1.5b`.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT t.line_no, t.line
FROM regexp_split_to_table(pg_get_functiondef('wdp.set_d_action_processed_est()'::regprocedure), E'\n')
     WITH ORDINALITY AS t(line, line_no)
ORDER BY t.line_no;
```
Then run `ROLLBACK;`.

### B1.6 Column list with types, defaults and generation (all three tables, both schemas)
This is the only `nap` DDL source, because the folder has none.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT table_schema, table_name, ordinal_position, column_name,
       data_type, udt_name, character_maximum_length, numeric_precision, numeric_scale,
       datetime_precision, is_nullable, column_default, is_generated, generation_expression
FROM information_schema.columns
WHERE table_schema IN ('wdp','nap')
  AND table_name IN ('case','action','case_lock')
ORDER BY table_schema, table_name, ordinal_position;
```

### B1.7 Column statistics for the filter and sort columns (aggregates only)
This is an addition beyond the brief. `null_frac` on `d_action_processed_est` answers Part A §A7, and `correlation` shows how well an index on a date column would read.
- `most_common_vals` and `histogram_bounds` are deliberately **not** selected, because they hold entity values.
- `n_distinct` is a count: a negative number means a fraction of the row count.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT schemaname, tablename, attname, null_frac, avg_width, n_distinct, correlation
FROM pg_stats
WHERE schemaname IN ('wdp','nap')
  AND tablename IN ('case','action','case_lock')
  AND attname IN ('d_action_processed','d_action_processed_est','z_insrt','c_action_sta','c_ownr',
                  'c_migration_sta','i_case_id','i_action_id','c_acq_platform','c_case_sta',
                  'c_level1_entity','c_level2_entity','c_level4_entity','c_level5_entity','i_case')
ORDER BY 1, 2, 3;
```

### B1.7b (US only; added 2026-11-11 after B1.7): `d_action_processed_est` coverage by month
**Why:** B1.7 shows `null_frac` = 0.435 for `wdp.action.d_action_processed_est`, so about 43% of action rows have no EST date. US activities filters on that column (Part A §A1, §A7), so any window that falls where it is NULL returns nothing for those rows.
- This query shows **from which month** the column is filled.
- It also tests whether the filled values follow the rule "Eastern date of `z_insrt` read as UTC", without needing B1.5b.
- The output is counts only.
- **Run time class:** medium–heavy (one full pass over `wdp.action`, 21 GB). Use a replica or run off-peak.
- **Result file:** append to `results/b1-us-indexes.txt` under `### B1.7b`.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '20min';
SET LOCAL lock_timeout = '5s';

SELECT date_trunc('month', a.d_action_processed)::date                                   AS month_plain,
       count(*)                                                                          AS n_rows,
       count(*) FILTER (WHERE a.d_action_processed_est IS NULL)                          AS n_est_null,
       round(100.0 * count(*) FILTER (WHERE a.d_action_processed_est IS NULL) / count(*), 2) AS pct_est_null,
       count(*) FILTER (WHERE a.d_action_processed_est = a.d_action_processed)           AS n_est_eq_plain,
       count(*) FILTER (WHERE a.d_action_processed_est = a.d_action_processed - 1)       AS n_est_plain_minus_1,
       count(*) FILTER (WHERE a.d_action_processed_est IS NOT NULL
                          AND a.d_action_processed_est NOT IN (a.d_action_processed, a.d_action_processed - 1)) AS n_est_other,
       count(*) FILTER (WHERE a.d_action_processed_est IS NOT NULL
                          AND a.d_action_processed_est <> (a.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York')::date) AS n_est_not_rule,
       count(*) FILTER (WHERE a.z_insrt IS NULL)                                         AS n_zinsrt_null
FROM wdp.action a
GROUP BY 1
ORDER BY 1 NULLS FIRST;
```
How to read it:
- `pct_est_null` drops to about 0 from the month the trigger and backfill cover.
- `n_est_not_rule` = 0 means every filled value matches the proposal's rule.
- `n_est_plain_minus_1` shows how often the Eastern date falls one day before the plain date.

### B1.8 Optional: does the running COMP-27 SQL filter on `_EST`? (`pg_stat_statements`, no values)
This settles Part A §A9 from the database side. It outputs **flags and totals only**, never query text. `pg_stat_statements` replaces every constant with `$n`, and the bound entity values never appear.
- If you get `relation "pg_stat_statements" does not exist`, write "not installed" in the file.
- If it returns 0 rows, your role may lack `pg_read_all_stats` (other users' text shows as `<insufficient privilege>`). Note that too.
- The view covers this instance only, so run it on the instance COMP-27 reads from, if you know which one (gap G15).
- On PostgreSQL 12 the time columns are named `total_time` and `max_time`. Rename them if you get a column error.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '60s';
SET LOCAL lock_timeout = '5s';

SELECT CASE WHEN query ILIKE '%nap."case"%' THEN 'nap' ELSE 'wdp' END                      AS schema,
       CASE WHEN query ILIKE '%SELECT count(*) FROM LATEST_ACTIONS LIMIT%' THEN 'activities_count'
            WHEN query ILIKE '%ORDER BY actionProcessed%LIMIT%'           THEN 'search_page (activities or UI V2)'
            ELSE 'other_case_action_join' END                                            AS kind,
       (query ILIKE '%A.D_ACTION_PROCESSED_EST BETWEEN%')                                AS filter_on_est,
       (query ILIKE '%A.D_ACTION_PROCESSED BETWEEN%')                                    AS filter_on_plain,
       (query ILIKE '%ORDER BY actionProcessedESTDate%')                                 AS sort_on_est,
       count(*)                                                                          AS n_statement_shapes,
       sum(calls)                                                                        AS calls,
       round(sum(total_exec_time)::numeric / 1000, 1)                                    AS total_exec_s,
       round((sum(total_exec_time) / nullif(sum(calls), 0))::numeric, 1)                 AS mean_ms,
       round(max(max_exec_time)::numeric, 1)                                             AS max_ms,
       sum(rows)                                                                         AS rows,
       sum(shared_blks_hit)                                                              AS shared_blks_hit,
       sum(shared_blks_read)                                                             AS shared_blks_read
FROM pg_stat_statements
WHERE query ILIKE '%INNER JOIN wdp.action a ON c.I_CASE_ID%'
   OR query ILIKE '%INNER JOIN nap."action" a ON c.I_CASE_ID%'
GROUP BY 1, 2, 3, 4, 5
ORDER BY 1, 2, 3, 4, 5;
```
The `activities_count` text (`… FROM LATEST_ACTIONS LIMIT 1`) is produced only by `getCountForChargeBackSearch` (US `C27/dao/impl/USDisputeCaseSearchDAOImpl.java:58`, UK `C27/dao/impl/UKDisputeCaseSearchDAOImpl.java:64`). The UI count uses a `CASE WHEN COUNT(*) > …` form (US `:76-78`), so that row identifies the activities endpoint. The page text is shared with UI V2 search (Part A §A8).

---

## B2. Cases per MID, and actions per MID in the last 61 days (aggregates only)

MID = `case.c_level1_entity`. Static filters (Part A, and `GDP-ACTIVITIES-SCOPE-STATS.md` §1.3):
- platform: US `CORE`, `PIN`; UK `NAP`, `LATAM`;
- `c_case_sta IN ('OPEN','CLOSED')`;
- for actions, `c_action_sta IN ('OPEN','ERROR','CLOSED')` (the default, `actionable` not set).

The 61-day window is `current_date - 60 … current_date`, which is the widest window COMP-27 accepts. The output is one row per metric: number of MIDs, MIDs with 0, min, p50, p90, p99, max and total. Cases with a NULL MID are counted separately and are not part of the distribution.

Part A is conclusive for the snapshot (US filters on `_EST`), but production is only medium-confidence, so the US query returns **both** variants in one pass. UK has only the plain column (Part A §A1).

### B2-US
- **Database:** US. **Run time class:** heavy (two passes over `wdp.case`; one pass over `wdp.action` with an OR on two date columns, probably a sequential scan). Run off-peak.
- **Result file:** `results/b2-us-mid.txt`
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '20min';
SET LOCAL lock_timeout = '5s';

WITH case_per_mid AS (
  SELECT c.c_level1_entity AS mid, count(*) AS n_cases
  FROM wdp."case" c
  WHERE c.c_acq_platform IN ('CORE','PIN')
    AND c.c_case_sta IN ('OPEN','CLOSED')
  GROUP BY 1
),
act_per_mid AS (
  SELECT c.c_level1_entity AS mid,
         count(*) FILTER (WHERE a.d_action_processed_est BETWEEN current_date - 60 AND current_date) AS n_act_est,
         count(*) FILTER (WHERE a.d_action_processed     BETWEEN current_date - 60 AND current_date) AS n_act_plain
  FROM wdp.action a
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  WHERE c.c_acq_platform IN ('CORE','PIN')
    AND c.c_case_sta IN ('OPEN','CLOSED')
    AND a.c_action_sta IN ('OPEN','ERROR','CLOSED')
    AND (a.d_action_processed_est BETWEEN current_date - 60 AND current_date
         OR a.d_action_processed  BETWEEN current_date - 60 AND current_date)
    AND c.c_level1_entity IS NOT NULL
  GROUP BY 1
),
per_mid AS (
  SELECT m.n_cases,
         coalesce(x.n_act_est, 0)   AS n_act_est,
         coalesce(x.n_act_plain, 0) AS n_act_plain
  FROM case_per_mid m
  LEFT JOIN act_per_mid x USING (mid)
  WHERE m.mid IS NOT NULL
)
SELECT v.metric,
       count(*)                                          AS n_mids,
       count(*) FILTER (WHERE v.n = 0)                   AS n_mids_zero,
       min(v.n)                                          AS min_n,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY v.n) AS p50_n,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY v.n) AS p90_n,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY v.n) AS p99_n,
       max(v.n)                                          AS max_n,
       sum(v.n)                                          AS total_n,
       (SELECT n_cases FROM case_per_mid WHERE mid IS NULL) AS cases_with_null_mid,
       current_date                                      AS window_end
FROM per_mid
CROSS JOIN LATERAL (VALUES ('cases_per_mid',            n_cases),
                           ('actions_61d_per_mid_EST',  n_act_est),
                           ('actions_61d_per_mid_PLAIN', n_act_plain)) AS v(metric, n)
GROUP BY v.metric
ORDER BY v.metric;
```

### B2-UK
- **Database:** UK. **Run time class:** medium (earlier UK case and action scans took 4–9 s).
- **Result file:** `results/b2-uk-mid.txt`
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15min';
SET LOCAL lock_timeout = '5s';

WITH case_per_mid AS (
  SELECT c.c_level1_entity AS mid, count(*) AS n_cases
  FROM nap."case" c
  WHERE c.c_acq_platform IN ('NAP','LATAM')
    AND c.c_case_sta IN ('OPEN','CLOSED')
  GROUP BY 1
),
act_per_mid AS (
  SELECT c.c_level1_entity AS mid, count(*) AS n_act
  FROM nap."action" a
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  WHERE c.c_acq_platform IN ('NAP','LATAM')
    AND c.c_case_sta IN ('OPEN','CLOSED')
    AND a.c_action_sta IN ('OPEN','ERROR','CLOSED')
    AND a.d_action_processed BETWEEN current_date - 60 AND current_date
    AND c.c_level1_entity IS NOT NULL
  GROUP BY 1
),
per_mid AS (
  SELECT m.n_cases, coalesce(x.n_act, 0) AS n_act
  FROM case_per_mid m
  LEFT JOIN act_per_mid x USING (mid)
  WHERE m.mid IS NOT NULL
)
SELECT v.metric,
       count(*)                                          AS n_mids,
       count(*) FILTER (WHERE v.n = 0)                   AS n_mids_zero,
       min(v.n)                                          AS min_n,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY v.n) AS p50_n,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY v.n) AS p90_n,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY v.n) AS p99_n,
       max(v.n)                                          AS max_n,
       sum(v.n)                                          AS total_n,
       (SELECT n_cases FROM case_per_mid WHERE mid IS NULL) AS cases_with_null_mid,
       current_date                                      AS window_end
FROM per_mid
CROSS JOIN LATERAL (VALUES ('cases_per_mid',             n_cases),
                           ('actions_61d_per_mid_PLAIN', n_act)) AS v(metric, n)
GROUP BY v.metric
ORDER BY v.metric;
```
If B1.6 shows `nap.action.d_action_processed` is a `timestamp`, tell me before running. The window would need `< current_date + 1` to match the date semantics.

---

## B3. Baseline plans for today's activities queries

### B3.0 Today's SQL, reconstructed from the code

**The call being reconstructed:** standard ACL-mode merchant call.
- Entity scope from the ACL, platform list from the ACL, date range set.
- `actionable` false, so no `actionStatus` or `owner`.
- No ARN, MID, action, stage, `productType` (non-partner) or `merchantId`.
- `maxAction` false.
- `pageSize` 200, `startRecordNumber` 1.

COMP-21 builders: US `J/service/impl/USCaseDetailsServiceImpl.java:165-211`; UK `J/service/impl/UKCaseDetailsServiceImpl.java:170-222`.

**Reconstruction status: best reconstruction from a transcribed snapshot** (Part A §0).
- The WHERE, ORDER BY and LIMIT text is generated by the code paths cited below, with no gaps.
- The SELECT lists were extracted mechanically from the string literals in the cited lines.
- What the snapshot cannot give:
  - whether the deployed build matches it;
  - the order of the entity-type groups inside the OR block (Java `HashMap` from `Collectors.groupingBy`, `J/utils/ACLUtils.java:40-44`) and of the platform list (`HashSet`, `:57-59`). Both are **not determinable**, and neither changes the plan;
  - whether every projected column exists in production. The folder DDL lacks `wdp.action.d_action_processed_est` (it exists in prod per q0), `wdp.case.c_au_pan` and `wdp.case.c_au_hash_pan`, and there is no `nap` DDL at all. **Run B3.0-check before B3.2.**

**Placeholders** (B3.1 prints ready-to-paste values):

| Placeholder | Meaning | App source |
|---|---|---|
| `<FROM_DATE>`, `<TO_DATE>` | `yyyy-MM-dd`, bound as `java.sql.Date` | US `C27/dao/impl/USDisputeCaseSearchDAOImpl.java:919-933`; UK `C27/dao/impl/UKDisputeCaseSearchDAOImpl.java:859-873` |
| `<PLATFORM_LIST>` | e.g. `'CORE','PIN'` (raw ACL `c_source_system` values plus `PIN` if `CORE`) | `J/utils/ACLUtils.java:55-69`; US `:498-503`, UK `:481-486` |
| `<ENTITY_PREDICATE>` | e.g. `(C.C_LEVEL4_ENTITY IN ('…','…') OR C.C_LEVEL5_ENTITY IN ('…'))`, one `IN` per entity-type code | US `:787-833`; UK `:715-761` |

The app binds every value as a `?` parameter. Here they are inlined as literals, which gives the planner the same information as the app's first executions (custom plans). See the caveat after B3.2.

**WHERE clause assembly.** The order is fixed by the code:
1. Action criteria come first (US `:425-426` → `:884`, `:931-933`; UK `:441-442` → `:823`, `:871-873`).
2. Case criteria follow (US `:427-428` → `:498-503`, `:510`, `:787-833`; UK `:443-444` → `:481-486`, `:494`, `:715-761`).
3. `WHERE` / `AND` joining: `C27/util/QueryAndParams.java:93-103`. OR joining: `:117-126`.

#### US count: `getCountForChargeBackSearch`
Cites: `USDisputeCaseSearchDAOImpl.java:55-58` (count wrapper), `:398-399` (CTE open), `:410` (head), `:417-422` (FROM), `:438-440` (CTE close).
```sql
WITH LATEST_ACTIONS AS ( SELECT C.I_CASE as caseNumber
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED')
    AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
    AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>)
    AND C.c_case_sta IN ('OPEN','CLOSED')
    AND <ENTITY_PREDICATE>
)
SELECT count(*) FROM LATEST_ACTIONS LIMIT 1;
```
The count is of **(case, action) rows**, not cases. The `LEFT JOIN case_lock` stays in the count, so if `case_lock.i_case` is not unique, a case with two lock rows counts twice (gap G8; B1.3 shows whether a unique constraint exists).

#### US page: `getSearchCase`
Cites: `:113-121`; head `:410`; projection `:316-376` (`:371`, `:377-383` commented out); FROM `:417-422`; sort and pagination `:1062-1068`, offset `:390`.
```sql
SELECT C.I_CASE as caseNumber
, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate,
  C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId,
  C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType,
  C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType,
  C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment,
  C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity,
  C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity,
  C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country,
  C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt,
  C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId,
  C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo,
  C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount,
  A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate,
  A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate,
  A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner,
  A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId,
  A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,
  C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency,
  C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason,
  C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent,
  A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent,
  A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus,
  C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4,
  C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp,
  C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode,
  CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock,
  cl.locked_by AS lockedBy
, case
  when A.C_ACTION_STA  != 'CLOSED' THEN 1
  when A.C_ACTION_STA = 'CLOSED' THEN 2
end AS sort_order_1,
case
  when A.C_ACTION_STA = 'CLOSED' then -1
  when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE  <= CURRENT_DATE THEN 0
  else A.D_EXPIRY_DUE - CURRENT_DATE
end AS sort_order_2
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED')
    AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
    AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>)
    AND C.c_case_sta IN ('OPEN','CLOSED')
    AND <ENTITY_PREDICATE>
  ORDER BY actionProcessedESTDate, actionId ASC
  LIMIT 200 OFFSET 0
```
`C.I_CASE as caseNumber` appears twice because the head (`:410`) and the projection (`:317`) both emit it. PostgreSQL accepts this.

#### UK count: `getCountForChargeBackSearch`
Cites: `UKDisputeCaseSearchDAOImpl.java:59-64`; CTE open `:403-409` (no `DISTINCT_MID`, because `napEntity` is null for this call, `C27/service/impl/ChargebackCaseSearchServiceImpl.java:65`); head `:421`; FROM `:428-429`, `:438`; CTE close `:454-456`.
```sql
WITH LATEST_ACTIONS AS ( SELECT C.I_CASE as caseNumber
  FROM  nap."case" c INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED')
 -- AND A.C_MIGRATION_STA = 'Y'      -- only when the NAP request has wdpMigrationStatus=true (J/…/UKCaseDetailsServiceImpl.java:208-212; UK DAO :844-847)
    AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
    AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>)
    AND C.c_case_sta IN ('OPEN','CLOSED')
    AND <ENTITY_PREDICATE>
)
SELECT count(*) FROM LATEST_ACTIONS LIMIT 1;
```

#### UK page: `getSearchCase`
Cites: `:98-108`; head `:421`; projection `:311-365` plus `:368-372` (the `isInternalUser = true` branch; the service passes `true` at `ChargebackCaseSearchServiceImpl.java:74`); FROM `:429`, `:438`; sort and pagination `:999-1005`, offset `:387`.
```sql
SELECT C.I_CASE as caseNumber
, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate,
  C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId,
  C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType,
  C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType,
  C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity,
  C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity,
  C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity,
  C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country,
  C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt,
  C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId,
  C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo,
  C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount,
  A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate,
  A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate,
  A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner,
  A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId,
  A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator,
  C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd,
  A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency,
  C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason,
  C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd,
  C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent,
  A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent,
  A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus,
  C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp,
  C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2,
  -- C.T_CUST_DSCRTN_3 as customerField3,   -- in the code (UK DAO :361) but NOT in UK-prod (B1.6, 2026-11-11); left out so the plan can run
  A.C_FUNCTION_CODE as functionCode,
  CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock,
  cl.locked_by AS lockedBy
, case
  when A.C_ACTION_STA  != 'CLOSED' THEN 1
  when A.C_ACTION_STA = 'CLOSED' THEN 2
end AS sort_order_1,
case
  when A.C_ACTION_STA = 'CLOSED' then -1
  when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE  <= CURRENT_DATE THEN 0
  else A.D_EXPIRY_DUE - CURRENT_DATE
end AS sort_order_2
  FROM  nap."case" c INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED')
 -- AND A.C_MIGRATION_STA = 'Y'      -- only with wdpMigrationStatus=true (see UK count)
    AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
    AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>)
    AND C.c_case_sta IN ('OPEN','CLOSED')
    AND <ENTITY_PREDICATE>
  ORDER BY actionProcessedDate, actionId ASC
  LIMIT 200 OFFSET 0
```

#### B3.0-check: do all columns the page query names exist? (run before B3.2)
- **Purpose:** list every column the reconstructed SQL uses that is **missing** in production. An empty result means the page SQL will parse.
- **Run time class:** light (catalog).
- **Result:** append to `results/b1-us-indexes.txt` / `results/b1-uk-indexes.txt` under `### B3.0-check`.
- If any row comes back, send it to me before running B3.2. A missing column would also mean the snapshot differs from production at that line.

**US database:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT 'wdp' AS schema, x.table_name, x.column_name AS missing_column
FROM (VALUES
  ('action','a_cnverted_dispute_amt'),('action','a_cnverted_dispute_exponent'),('action','a_dispute_amt'),
  ('action','a_dispute_workable_amt'),('action','a_post_amt'),('action','c_action_in_out'),
  ('action','c_action_sta'),('action','c_action_type'),('action','c_case_stage'),
  ('action','c_cnverted_dispute_amt_currency'),('action','c_dispute_currency'),
  ('action','c_dispute_workable_currency'),('action','c_function_code'),('action','c_incomng_partial'),
  ('action','c_issuer_doc_ind'),('action','c_migration_sta'),('action','c_mrch_doc_ind'),
  ('action','c_network_doc_ind'),('action','c_ntwk_chbk_refnce'),('action','c_ntwrk_phase_id'),
  ('action','c_ownr'),('action','c_partial'),('action','c_post_currency'),('action','c_pre_note'),
  ('action','c_reason'),('action','c_reason_category'),('action','c_reject_reason2'),
  ('action','c_revrsl_ind'),('action','c_source_case_id'),('action','c_spec_handling'),
  ('action','c_timeframe_expired_ind'),('action','d_action_processed'),('action','d_action_processed_est'),
  ('action','d_action_reported'),('action','d_expiry_due'),('action','d_reject'),('action','d_response_due'),
  ('action','i_action_id'),('action','i_action_seq'),('action','i_case_id'),
  ('action','i_dispute_currency_exponent'),('action','i_dispute_workable_currency_exponent'),
  ('action','i_post_amt_exponent'),('action','x_dspt_amt_sgn'),('action','x_insrt'),
  ('action','x_insrt_display'),('action','x_updt'),('action','x_updt_display'),('action','z_insrt'),
  ('action','z_updt'),('case','a_cnverted_purchase_amt'),('case','a_dispute_orig'),('case','a_purchase_amt'),
  ('case','c_acq_platform'),('case','c_acro_frm'),('case','c_acro_to'),('case','c_assignment_rsn'),
  ('case','c_au_hash_pan'),('case','c_au_pan'),('case','c_auth_tr'),('case','c_avs_resp'),
  ('case','c_case_final_liability'),('case','c_case_ntwk'),('case','c_case_special_handling'),
  ('case','c_case_sta'),('case','c_case_wrkflw_type'),('case','c_checkout_id'),('case','c_cntry'),
  ('case','c_cnverted_purchase_amt_currency'),('case','c_cust_field1'),('case','c_cvv'),
  ('case','c_enrich_failure'),('case','c_entry_mode'),('case','c_input_method'),('case','c_invoice'),
  ('case','c_level10_entity'),('case','c_level11_entity'),('case','c_level1_entity'),
  ('case','c_level2_entity'),('case','c_level3_entity'),('case','c_level4_entity'),('case','c_level5_entity'),
  ('case','c_level6_entity'),('case','c_level7_entity'),('case','c_level8_entity'),('case','c_level9_entity'),
  ('case','c_mcc'),('case','c_merchant_name'),('case','c_merchant_ref_number'),('case','c_mrchnt_order_id'),
  ('case','c_ntwk_case_id'),('case','c_product_sub_type'),('case','c_product_type'),
  ('case','c_purchase_currency'),('case','c_sig_pin'),('case','c_sls_pers_id'),('case','c_ste'),
  ('case','c_token'),('case','c_tr_type'),('case','d_case_end'),('case','d_case_strt'),('case','d_cse_ready'),
  ('case','d_departure'),('case','d_tr'),('case','d_tr_timestamp'),('case','i_acct_cdh'),
  ('case','i_acct_cdh_lst'),('case','i_acq_refnce_num'),('case','i_case'),('case','i_case_action_max_seq'),
  ('case','i_case_id'),('case','i_cnverted_purchase_amt_exponent'),('case','i_desk'),
  ('case','i_destination_ica'),('case','i_issuer_bin'),('case','i_ntwk_tran_id'),
  ('case','i_pan_first6_last4'),('case','i_purchase_amt_exponent'),('case','i_rgstr'),('case','i_source_ica'),
  ('case','i_ticket_number'),('case','i_wpy_settle_tranid'),('case','n_city'),('case','t_cust_dscrtn_2'),
  ('case','t_cust_dscrtn_3'),('case_lock','i_case'),('case_lock','locked_by')
) AS x(table_name, column_name)
LEFT JOIN information_schema.columns ic
       ON ic.table_schema = 'wdp' AND ic.table_name = x.table_name AND ic.column_name = x.column_name
WHERE ic.column_name IS NULL
ORDER BY 2, 3;
```

**UK database:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT 'nap' AS schema, x.table_name, x.column_name AS missing_column
FROM (VALUES
  ('action','a_cnverted_dispute_amt'),('action','a_cnverted_dispute_exponent'),('action','a_dispute_amt'),
  ('action','a_dispute_workable_amt'),('action','a_post_amt'),('action','c_action_in_out'),
  ('action','c_action_sta'),('action','c_action_type'),('action','c_case_stage'),
  ('action','c_cnverted_dispute_amt_currency'),('action','c_dispute_currency'),
  ('action','c_dispute_workable_currency'),('action','c_duplicate_ind'),('action','c_eql_recon_amt_ind'),
  ('action','c_function_code'),('action','c_incomng_partial'),('action','c_issuer_doc_ind'),
  ('action','c_migration_sta'),('action','c_mrch_doc_ind'),('action','c_network_doc_ind'),
  ('action','c_ntwk_chbk_refnce'),('action','c_ntwrk_phase_id'),('action','c_ownr'),('action','c_partial'),
  ('action','c_post_amt_exponent'),('action','c_post_currency'),('action','c_pre_note'),('action','c_reason'),
  ('action','c_reason_category'),('action','c_reject_reason2'),('action','c_revrsl_ind'),
  ('action','c_source_case_id'),('action','c_spec_handling'),('action','c_timeframe_expired_ind'),
  ('action','d_action_processed'),('action','d_action_reported'),('action','d_expiry_due'),
  ('action','d_reject'),('action','d_response_due'),('action','i_action_id'),('action','i_action_seq'),
  ('action','i_case_id'),('action','i_dispute_currency_exponent'),
  ('action','i_dispute_workable_currency_exponent'),('action','x_dspt_amt_sgn'),('action','x_insrt'),
  ('action','x_insrt_display'),('action','x_updt'),('action','x_updt_display'),('action','z_insrt'),
  ('action','z_updt'),('case','a_cnverted_purchase_amt'),('case','a_dispute_orig'),('case','a_purchase_amt'),
  ('case','c_acq_platform'),('case','c_assignment_rsn'),('case','c_au_hash_pan'),('case','c_au_pan'),
  ('case','c_auth_tr'),('case','c_avs_resp'),('case','c_case_final_liability'),('case','c_case_ntwk'),
  ('case','c_case_special_handling'),('case','c_case_sta'),('case','c_case_wrkflw_type'),
  ('case','c_checkout_id'),('case','c_cntry'),('case','c_cnverted_purchase_amt_currency'),
  ('case','c_confrm_fraud'),('case','c_confrm_refund'),('case','c_cust_field1'),('case','c_cvv'),
  ('case','c_enrich_failure'),('case','c_entry_mode'),('case','c_fns_counter'),('case','c_input_method'),
  ('case','c_level10_entity'),('case','c_level11_entity'),('case','c_level1_entity'),
  ('case','c_level2_entity'),('case','c_level3_entity'),('case','c_level4_entity'),('case','c_level5_entity'),
  ('case','c_level6_entity'),('case','c_level7_entity'),('case','c_level8_entity'),('case','c_level9_entity'),
  ('case','c_mcc'),('case','c_merchant_name'),('case','c_merchant_ref_number'),('case','c_mrchnt_order_id'),
  ('case','c_ntwk_case_id'),('case','c_product_sub_type'),('case','c_product_type'),
  ('case','c_purchase_currency'),('case','c_refund'),('case','c_sig_pin'),('case','c_ste'),('case','c_token'),
  ('case','c_tr_type'),('case','d_case_end'),('case','d_case_strt'),('case','d_cse_ready'),('case','d_fns'),
  ('case','d_tr'),('case','d_tr_timestamp'),('case','i_acct_cdh'),('case','i_acct_cdh_lst'),
  ('case','i_acq_refnce_num'),('case','i_case'),('case','i_case_action_max_seq'),('case','i_case_id'),
  ('case','i_cnverted_purchase_amt_exponent'),('case','i_desk'),('case','i_destination_ica'),
  ('case','i_issuer_bin'),('case','i_ntwk_tran_id'),('case','i_pan_first6_last4'),
  ('case','i_purchase_amt_exponent'),('case','i_rgstr'),('case','i_source_ica'),('case','i_ticket_number'),
  ('case','i_wpy_settle_tranid'),('case','n_city'),('case','t_cust_dscrtn_2'),('case','t_cust_dscrtn_3'),
  ('case_lock','i_case'),('case_lock','locked_by')
) AS x(table_name, column_name)
LEFT JOIN information_schema.columns ic
       ON ic.table_schema = 'nap' AND ic.table_name = x.table_name AND ic.column_name = x.column_name
WHERE ic.column_name IS NULL
ORDER BY 2, 3;
```

---

### B3.1 Consumer picker (for your eyes only)

> **DO NOT SAVE the output of B3.1. Do not paste it back to me.** It prints real entity values (and, for US, the consumer name). Use it only to fill the B3.2 placeholders on your screen, then close the result tab. The only entity values that may later leave your screen are the ones in the B3.2 plans, and those must be replaced with `<V1>, <V2>, …` before saving.

**How "size" is defined:** the in-scope **case** count. That is the distinct cases matching the consumer's ACL scope, platform list and `c_case_sta IN ('OPEN','CLOSED')`, as COMP-21 → COMP-27 would build it (`J/utils/ACLUtils.java:37-69, 94-139`; `GDP-ACTIVITIES-SCOPE-STATS.md` §1.2).
- Only consumers with at least one in-scope case are ranked.
- *Median* is the consumer at rank ⌈n/2⌉; *largest* is rank n. Ties are broken by name or key.
- The ACL is read from `wdp.acl` with `c_status = 'ACTIVE'`, the status COMP-21 requests (`J/utils/ApplicationConstants.java:87`; `J/controller/UKCaseController.java:64`). That the ACL service is backed by this table is an assumption carried from earlier work. **Not determinable** from these two services.

Each picker also prints the dates to paste:
- `<TO_DATE>` = yesterday (the last complete day);
- 2-day range: `<FROM_DATE>` = `TO − 1`;
- 60-day range: `<FROM_DATE>` = `TO − 60`. That gives `DAYS.between = 60`, the widest COMP-27 accepts (`C27/util/RequestValidator.java:439-441`).

#### B3.1-US (run on US)
**Run time class:** heavy, about 1.5 min (same joins as the earlier query d).
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30min';
SET LOCAL lock_timeout = '5s';

WITH acl_us AS (               -- COMP-21 US filter: J/utils/ACLUtils.java:115-131
  SELECT c_consumer_name, c_source_system, c_entity_type, c_entity_value
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('CORE','VAP','PIN')
    AND c_entity_type IN ('Chain','OrgID','SuperChain')
),
consumer AS (
  SELECT c_consumer_name,
         CASE WHEN bool_or(c_entity_type = 'OrgID') AND bool_or(c_entity_type <> 'OrgID') THEN 'EMPTY_200 on /cases/activities'
              WHEN bool_and(c_entity_type = 'OrgID')                                      THEN 'DS on /cases/activities'
              ELSE 'COMP27 on both' END                                                   AS route,
         array_agg(DISTINCT c_source_system::text)
           || CASE WHEN bool_or(c_source_system = 'CORE') THEN ARRAY['PIN'] ELSE ARRAY[]::text[] END AS platforms
  FROM acl_us
  GROUP BY 1
),
consumer_cases AS (
  SELECT u.c_consumer_name, c.i_case_id
  FROM acl_us u JOIN consumer k USING (c_consumer_name)
  JOIN wdp."case" c ON c.c_level4_entity = u.c_entity_value
  WHERE u.c_entity_type IN ('Chain','OrgID')
    AND c.c_acq_platform = ANY (k.platforms) AND c.c_case_sta IN ('OPEN','CLOSED')
  UNION
  SELECT u.c_consumer_name, c.i_case_id
  FROM acl_us u JOIN consumer k USING (c_consumer_name)
  JOIN wdp."case" c ON c.c_level5_entity = u.c_entity_value
  WHERE u.c_entity_type = 'SuperChain'
    AND c.c_acq_platform = ANY (k.platforms) AND c.c_case_sta IN ('OPEN','CLOSED')
),
ranked AS (
  SELECT c_consumer_name, count(*) AS n_cases,
         row_number() OVER (ORDER BY count(*), c_consumer_name) AS rn,
         count(*) OVER ()                                        AS n
  FROM consumer_cases
  GROUP BY c_consumer_name
),
picks AS (
  SELECT 'median' AS pick, * FROM ranked WHERE rn = (n + 1) / 2
  UNION ALL
  SELECT 'largest', * FROM ranked WHERE rn = n
)
SELECT p.pick, p.c_consumer_name, k.route, p.n_cases, p.n AS n_ranked_consumers,
       (SELECT string_agg(quote_literal(x), ',' ORDER BY x) FROM unnest(k.platforms) x) AS platform_list,
       (SELECT '(' || string_agg(col || ' IN (' || vals || ')', ' OR ' ORDER BY col, code) || ')'
          FROM (SELECT CASE u.c_entity_type WHEN 'SuperChain' THEN 'C.C_LEVEL5_ENTITY' ELSE 'C.C_LEVEL4_ENTITY' END AS col,
                       u.c_entity_type AS code,
                       string_agg(quote_literal(u.c_entity_value), ',' ORDER BY u.c_entity_value) AS vals
                FROM acl_us u WHERE u.c_consumer_name = p.c_consumer_name
                GROUP BY 1, 2) g)                                                   AS entity_predicate,
       (SELECT count(*) FROM acl_us u WHERE u.c_consumer_name = p.c_consumer_name)  AS n_values,
       to_char(current_date - 1,  'YYYY-MM-DD') AS to_date,
       to_char(current_date - 2,  'YYYY-MM-DD') AS from_date_2d,
       to_char(current_date - 61, 'YYYY-MM-DD') AS from_date_60d
FROM picks p JOIN consumer k USING (c_consumer_name)
ORDER BY p.pick DESC;
```
If the median or largest consumer's `route` is `EMPTY_200`/`DS on /cases/activities`, it still reaches COMP-27 through `/us/cases/activities` (Part A, call path), so its plan is still valid.

#### B3.1-UK, step 1 (run on **US**): generate the UK picker
The ACL lives in the US database and the cases in the UK database, and there is no cross-database join. This step **prints a SQL statement** (one text cell) with the UK ACL rows embedded under an opaque numeric key. You then run that statement on UK in step 2. The UK ACL is small (32 active rows, 24 consumers, at most 3 values each, per a1/a2 on 2026-11-10), so the cell is short.

**Run time class:** light.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '60s';
SET LOCAL lock_timeout = '5s';

WITH acl_uk AS (               -- COMP-21 UK filter: J/utils/ACLUtils.java:97-109
  SELECT c_consumer_name, c_source_system, c_entity_type, c_entity_value
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('NAP','LATAM')
    AND c_entity_type IN ('Company ID','Group ID','Outlet ID')
),
keyed AS (
  SELECT dense_rank() OVER (ORDER BY c_consumer_name) AS k,
         CASE c_entity_type WHEN 'Company ID' THEN 'CO' WHEN 'Group ID' THEN 'GR' ELSE 'OL' END AS code,
         c_source_system, c_entity_value
  FROM acl_uk
)
SELECT
  'WITH acl(k, code, val) AS (VALUES '
  || (SELECT string_agg(format('(%s,%L,%L)', k, code, c_entity_value), ',') FROM keyed)
  || '), plat(k, p) AS (VALUES '
  || (SELECT string_agg(DISTINCT format('(%s,%L)', k, c_source_system), ',') FROM keyed)
  || ')'
  || $tail$
, hits AS (
  SELECT a.k, c.i_case_id, c.c_acq_platform FROM acl a JOIN nap."case" c ON c.c_level4_entity = a.val
   WHERE a.code = 'CO' AND c.c_case_sta IN ('OPEN','CLOSED')
  UNION
  SELECT a.k, c.i_case_id, c.c_acq_platform FROM acl a JOIN nap."case" c ON c.c_level5_entity = a.val
   WHERE a.code = 'GR' AND c.c_case_sta IN ('OPEN','CLOSED')
  UNION
  SELECT a.k, c.i_case_id, c.c_acq_platform FROM acl a JOIN nap."case" c ON c.c_level2_entity = a.val
   WHERE a.code = 'OL' AND c.c_case_sta IN ('OPEN','CLOSED')
),
scoped AS (
  SELECT h.k, count(DISTINCT h.i_case_id) AS n_cases
  FROM hits h JOIN plat p ON p.k = h.k AND p.p = h.c_acq_platform
  GROUP BY h.k
),
ranked AS (
  SELECT k, n_cases, row_number() OVER (ORDER BY n_cases, k) AS rn, count(*) OVER () AS n
  FROM scoped WHERE n_cases > 0
),
picks AS (
  SELECT 'median' AS pick, k, n_cases, n FROM ranked WHERE rn = (n + 1) / 2
  UNION ALL
  SELECT 'largest', k, n_cases, n FROM ranked WHERE rn = n
)
SELECT pk.pick, pk.k AS consumer_key, pk.n_cases, pk.n AS n_ranked_consumers,
       (SELECT string_agg(quote_literal(p), ',' ORDER BY p) FROM plat WHERE plat.k = pk.k) AS platform_list,
       (SELECT '(' || string_agg(col || ' IN (' || vals || ')', ' OR ' ORDER BY col) || ')'
          FROM (SELECT CASE code WHEN 'CO' THEN 'C.C_LEVEL4_ENTITY' WHEN 'GR' THEN 'C.C_LEVEL5_ENTITY'
                                 ELSE 'C.C_LEVEL2_ENTITY' END AS col,
                       string_agg(quote_literal(val), ',' ORDER BY val) AS vals
                FROM acl WHERE acl.k = pk.k GROUP BY code) g)                      AS entity_predicate,
       (SELECT count(*) FROM acl WHERE acl.k = pk.k)                              AS n_values,
       to_char(current_date - 1,  'YYYY-MM-DD') AS to_date,
       to_char(current_date - 2,  'YYYY-MM-DD') AS from_date_2d,
       to_char(current_date - 61, 'YYYY-MM-DD') AS from_date_60d
FROM picks pk
ORDER BY pk.pick DESC;
$tail$ AS uk_picker_sql;
```
Copy the single `uk_picker_sql` cell, for example by double-clicking it and pressing Ctrl+A, Ctrl+C, or with *Save results to file*. Do not save it to `docs/`; it contains entity values.

#### B3.1-UK, step 2 (run on **UK**)
**Run time class:** medium.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15min';
SET LOCAL lock_timeout = '5s';

-- paste the uk_picker_sql cell from step 1 here
```
If it fails with `operator does not exist: … = text` on a level column, that column is not character-typed in `nap`. Send me only the error text, without values. The app binds these values as strings (UK DAO `:715-761`), so the same mismatch would also break the app's own query.

---

### B3.2 EXPLAIN blocks: baseline plans

**Matrix:** consumer size {median, largest} × range {2d, 60d} × query {count, page} = **8 plans per region**.

**Templates.** For each plan:
1. Take the template for the region and query type below.
2. Fill in the four placeholders from the B3.1 output for the chosen consumer: `<PLATFORM_LIST>`, `<ENTITY_PREDICATE>`, `<FROM_DATE>`, `<TO_DATE>`.
3. **Run the block twice. Save only the second run** (the first warms the cache).
4. Run `ROLLBACK;` between runs.

**Before saving, sanitise the plan text:**
- Replace **every entity value** with `<V1>`, `<V2>`, …, using the same token for the same value everywhere in the file. Values appear in `Filter:`, `Index Cond:`, `Recheck Cond:` and `Hash Cond:` lines, often as `ANY ('{…}'::text[])`.
- For a long list, replacing the whole array literal with `'{<V1>,…,<Vn>}'` (n = number of values) is acceptable.
- Search the file for each value from the B3.1 output before saving.
- Dates and platform codes (`CORE`, `PIN`, `VAP`, `NAP`, `LATAM`) may stay.
- Also check the `Settings:` line. It should hold only planner settings.

**If a block times out** (10 min), save the line `timeout at 10min`. Then run the **same block with `EXPLAIN (BUFFERS, SETTINGS)` and no `ANALYZE`**, which only plans and does not execute, and save that plan under the same file name.

| # | File (`docs/results/…`) | DB | Template | Consumer | `<FROM_DATE>` | `<TO_DATE>` | Run time class |
|---|---|---|---|---|---|---|---|
| 1 | `b3-us-median-2d-count.txt` | US | US-COUNT | median (B3.1-US) | `from_date_2d` | `to_date` | light–medium |
| 2 | `b3-us-median-2d-page.txt` | US | US-PAGE | median | `from_date_2d` | `to_date` | light–medium |
| 3 | `b3-us-median-60d-count.txt` | US | US-COUNT | median | `from_date_60d` | `to_date` | medium |
| 4 | `b3-us-median-60d-page.txt` | US | US-PAGE | median | `from_date_60d` | `to_date` | medium |
| 5 | `b3-us-largest-2d-count.txt` | US | US-COUNT | largest | `from_date_2d` | `to_date` | medium |
| 6 | `b3-us-largest-2d-page.txt` | US | US-PAGE | largest | `from_date_2d` | `to_date` | medium |
| 7 | `b3-us-largest-60d-count.txt` | US | US-COUNT | largest | `from_date_60d` | `to_date` | heavy |
| 8 | `b3-us-largest-60d-page.txt` | US | US-PAGE | largest | `from_date_60d` | `to_date` | heavy |
| 9 | `b3-uk-median-2d-count.txt` | UK | UK-COUNT | median (B3.1-UK) | `from_date_2d` | `to_date` | light |
| 10 | `b3-uk-median-2d-page.txt` | UK | UK-PAGE | median | `from_date_2d` | `to_date` | light |
| 11 | `b3-uk-median-60d-count.txt` | UK | UK-COUNT | median | `from_date_60d` | `to_date` | light–medium |
| 12 | `b3-uk-median-60d-page.txt` | UK | UK-PAGE | median | `from_date_60d` | `to_date` | light–medium |
| 13 | `b3-uk-largest-2d-count.txt` | UK | UK-COUNT | largest | `from_date_2d` | `to_date` | light–medium |
| 14 | `b3-uk-largest-2d-page.txt` | UK | UK-PAGE | largest | `from_date_2d` | `to_date` | light–medium |
| 15 | `b3-uk-largest-60d-count.txt` | UK | UK-COUNT | largest | `from_date_60d` | `to_date` | medium |
| 16 | `b3-uk-largest-60d-page.txt` | UK | UK-PAGE | largest | `from_date_60d` | `to_date` | medium |

At the top of each saved file, add one header line: the date and time you ran it, and `n_values` / `n_cases` from B3.1. Those are counts, so they are fine to keep.

#### Template US-COUNT (run on US)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH LATEST_ACTIONS AS ( SELECT C.I_CASE as caseNumber
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED')
    AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
    AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>)
    AND C.c_case_sta IN ('OPEN','CLOSED')
    AND <ENTITY_PREDICATE>
)
SELECT count(*) FROM LATEST_ACTIONS LIMIT 1;
```

#### Template US-PAGE (run on US)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
-- paste the full "US page" SQL from B3.0 here, with the four placeholders filled in
```

#### Template UK-COUNT (run on UK)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH LATEST_ACTIONS AS ( SELECT C.I_CASE as caseNumber
  FROM  nap."case" c INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED')
    AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
    AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>)
    AND C.c_case_sta IN ('OPEN','CLOSED')
    AND <ENTITY_PREDICATE>
)
SELECT count(*) FROM LATEST_ACTIONS LIMIT 1;
```

#### Template UK-PAGE (run on UK)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
-- paste the full "UK page" SQL from B3.0 here, with the four placeholders filled in
```

**Caveats on these plans:**
- **Custom plan vs generic plan.** COMP-27 sends bound parameters through the JDBC driver. Inlined literals reproduce the planner's per-execution (custom) plan. Whether the deployed driver switches a hot statement to a cached generic plan depends on driver settings and `plan_cache_mode` (B1.0), which are **not determinable from source**. If B1.8 shows high call counts and the custom plans look fine, a generic-plan check can be added later.
- **EXPLAIN ANALYZE runs the query.** It executes fully on the replica but returns only the plan. That is why every block has a 10-minute timeout.
- **Settings line.** `SETTINGS` lists only planner settings that differ from the defaults.

---

## Checklist of expected result files (`docs/results/`)

| ☐ | File | From | DB |
|---|---|---|---|
| ☐ | `b1-us-indexes.txt` | B1.0–B1.8, plus `### B3.0-check` | US |
| ☐ | `b1-uk-indexes.txt` | B1.0–B1.8, plus `### B3.0-check` | UK |
| ☐ | `b2-us-mid.txt` | B2-US | US |
| ☐ | `b2-uk-mid.txt` | B2-UK | UK |
| ☐ | `b3-us-median-2d-count.txt` | B3.2 #1 | US |
| ☐ | `b3-us-median-2d-page.txt` | B3.2 #2 | US |
| ☐ | `b3-us-median-60d-count.txt` | B3.2 #3 | US |
| ☐ | `b3-us-median-60d-page.txt` | B3.2 #4 | US |
| ☐ | `b3-us-largest-2d-count.txt` | B3.2 #5 | US |
| ☐ | `b3-us-largest-2d-page.txt` | B3.2 #6 | US |
| ☐ | `b3-us-largest-60d-count.txt` | B3.2 #7 | US |
| ☐ | `b3-us-largest-60d-page.txt` | B3.2 #8 | US |
| ☐ | `b3-uk-median-2d-count.txt` | B3.2 #9 | UK |
| ☐ | `b3-uk-median-2d-page.txt` | B3.2 #10 | UK |
| ☐ | `b3-uk-median-60d-count.txt` | B3.2 #11 | UK |
| ☐ | `b3-uk-median-60d-page.txt` | B3.2 #12 | UK |
| ☐ | `b3-uk-largest-2d-count.txt` | B3.2 #13 | UK |
| ☐ | `b3-uk-largest-2d-page.txt` | B3.2 #14 | UK |
| ☐ | `b3-uk-largest-60d-count.txt` | B3.2 #15 | UK |
| ☐ | `b3-uk-largest-60d-page.txt` | B3.2 #16 | UK |

**Not saved, by design:** the B3.1-US output, the B3.1-UK step 1 cell and the step 2 output.

**Still to run at the end (status 2026-11-11):**
- **B1.5b (US only, required):** see section B1.5b above.
- **B1.7b (US only, heavy, off-peak):** see section B1.7b above.
- **B1.8:** done. `pg_stat_statements` is not installed in US or UK.
- **B3.0-check** does not need running: it was computed from B1.6 on 2026-11-11. US has 0 missing columns. UK has 1 missing, `nap.case.t_cust_dscrtn_3`, which is now commented out in the UK page SQL.

**Suggested order:**
1. B1 on both regions, including B3.0-check.
2. Send me B3.0-check if it returned any rows.
3. B2 (off-peak for US).
4. B3.1.
5. B3.2, smallest first: 2d before 60d, median before largest.

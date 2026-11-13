# GDP-ACTIVITIES-PHASE1B-SQL.md
**Phase 1B, Stage 1: make load comparable, then measure setups A and B (old SQL without and with the Stage 2 indexes)**
*2026-11-12. LOAD only; nothing here runs in production. Written for you to run; I have no database access. Companions: `GDP-ACTIVITIES-PHASE1-VERIFY-REPORT.md` (section 0: load facts and date windows), `GDP-ACTIVITIES-PHASE1-INDEXES.md` (Stage 2 indexes), `GDP-ACTIVITIES-FINALIZE-SQL.md` (old-SQL templates, B3).*

Setup C (new SQL) and the equivalence, concurrency and write-cost tests are added to this file in Stage 3.

---

## 0. How to run

**What writes to load:** only the maintenance blocks M1 (VACUUM), X (set the Stage 2 indexes aside) and R (restore them). Everything else is read-only: each block opens `BEGIN TRANSACTION READ ONLY` with its own `statement_timeout`.

**Two ways to run the matrix:**
- **Recommended: the psql drivers** in `docs/phase1b-scripts/`. They contain **no entity values**: the consumers come in as psql variables from the picker.
  - Each one runs every matrix cell twice, in one read-only transaction per cell, and writes only the second run to its own file.
  - It also works out the deep-page offsets, and writes the noise files.
  - Use psql (`C:\Program Files\PostgreSQL\17\bin\psql.exe`) or pgAdmin's *PSQL Tool*, connected to load.
  - Checked end to end on local PostgreSQL 17.10 against throwaway copies of the load table shapes: 0 errors, 46 output files per region per setup, deep page and "n/a" branches both exercised.
- **Manual: pgAdmin.** Use the four templates in §6.3, one cell at a time, run twice, keep the second.

**Where raw output goes:**
- Raw plans contain entity values in `Filter` / `Index Cond` lines. Write them to a **scratch folder outside `docs/`**, for example `gdp-search/images/p1b/` (create it).
- I redact them into `docs/results-phase1b/` with the same file names.
- This differs from the brief, which says "save under `docs/results-phase1b/`". Raw plans must not land in `docs/`.

**Date windows** (load has full volume there; VERIFY report §0):

| Region | `TO` | 1 d | 2 d | 7 d | 30 d | 60 d |
|---|---|---|---|---|---|---|
| US (`d_action_processed_est`) | 2026-10-05 | 2026-10-05 | 2026-10-04 | 2026-09-29 | 2026-09-06 | 2026-08-06 |
| UK (`d_action_processed`) | 2025-09-30 | 2025-09-30 | 2025-09-29 | 2025-09-24 | 2025-09-01 | 2025-08-01 |

The cells show `FROM` (`BETWEEN FROM AND TO`, inclusive).
- An *n*-day range is *n* calendar days.
- **60 d** is `DAYS.between = 60`, which is 61 calendar days: the widest COMP-27 accepts, and the same window as Phase 1, so the Phase 1 results stay comparable.

**UK page in load** omits `customerField1/2/3`, because load `nap.case` lacks those columns (FINALIZE B3.0-check). The drivers and the §6.3 template already do this.

---

## 1. Order of operations

| # | Step | Writes? | Section | Saves (results) |
|---|---|---|---|---|
| 1 | VACUUM (ANALYZE) the 4 tables | **yes** (maintenance) | M1 | — |
| 2 | Visibility / last vacuum check | no | M2 | `p1b-visibility.txt` |
| 3 | Pickers: record version (save) and eyes-only version (make your local vars file) | no | §2 | `p1b-us-consumers.txt`, `p1b-uk-consumers.txt` |
| 4 | **Set the Stage 2 indexes aside**, then check that 7 rows show `present = false` | **yes** | §4 X, X-check | `p1b-index-setaside.txt` |
| 5 | **Setup A**: cold scripts first (US, UK), then the drivers with `\set setup a` | no | §5, §6 | `a-*.txt` (96 files) |
| 6 | **Restore the Stage 2 indexes**, then check that 7 rows are present, valid and ready | **yes** | §4 R, R-check | `p1b-index-restore.txt` |
| 7 | **Setup B**: cold scripts first, then the drivers with `\set setup b` | no | §5, §6 | `b-*.txt` (96 files) |
| 8 | Tell me **"results ready"** | | | |

- **Why this order:** setup A needs the old SQL without the Stage 2 indexes. They already exist in load (Phase 1 L3), so step 4 sets them aside and step 6 rebuilds them from the same definitions. VACUUM runs once, before both, so A and B see the same visibility.
- Between steps 1 and 7, avoid other load activity that writes to these tables. If any happens, note it. B1.1 showed US `n_mod_since_analyze = 0`, so load is normally quiet.
- **Re-running a step:** steps 5 and 7 overwrite their own files. Steps 4 and 6 are idempotent (`IF EXISTS` / `IF NOT EXISTS`).

---

## M. Maintenance (step 1) and visibility check (step 2)

### M1. VACUUM (ANALYZE)
- **Effect:** marks pages all-visible and refreshes the planner statistics. Not FULL: no table rewrite, no exclusive lock. Reads and writes continue.
- **Run time:** US `action` is 7.7 GB plus 12 GB of indexes, so expect tens of minutes. The others are smaller.
- **After this, load is "freshly vacuumed",** which prod is not: prod UK `action` was 64 % all-visible and US `case` 91 % on 2026-11-11. The Stage 3 report states this next to every number.
```sql
-- maintenance, LOAD only. Not inside a transaction (pgAdmin auto-commit on). One statement at a time.
VACUUM (ANALYZE, VERBOSE) wdp."case";
VACUUM (ANALYZE, VERBOSE) wdp.action;
VACUUM (ANALYZE, VERBOSE) nap."case";
VACUUM (ANALYZE, VERBOSE) nap."action";
```
### M2. Visibility and last vacuum / analyze (save to `docs/results-phase1b/p1b-visibility.txt`; counts only)
Expect `pct_all_visible` close to 100 % for the 4 tables, and today's date in `last_vacuum` / `last_analyze`.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT n.nspname AS schema, c.relname AS table_name, c.relpages, c.relallvisible,
       round(100.0 * c.relallvisible / NULLIF(c.relpages, 0), 1) AS pct_all_visible,
       s.n_live_tup, s.n_dead_tup, s.n_mod_since_analyze,
       s.last_vacuum, s.last_autovacuum, s.last_analyze, s.last_autoanalyze,
       pg_size_pretty(pg_table_size(c.oid)) AS table_size, pg_size_pretty(pg_indexes_size(c.oid)) AS indexes_size
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
WHERE c.relkind = 'r'
  AND (n.nspname, c.relname) IN (('wdp','case'),('wdp','action'),('wdp','case_lock'),('nap','case'),('nap','action'),('nap','case_lock'))
ORDER BY 1, 2;
```
---

## 2. Test consumers (step 3)

**Size classes:** consumers are ranked by in-scope case count. The scope is the ACL entity values + platforms + `c_case_sta IN ('OPEN','CLOSED')`, the same ranking as FINALIZE B3.1 / VERIFY P1.

| Class | Rank |
|---|---|
| **small** | p10, `ceil(0.10·n)` |
| **median** | `(n+1)/2`, the same pick as Phase 1 |
| **large** | p90, `ceil(0.90·n)` |
| **largest** | `n`, the largest available in load |

In load, `wdp.acl` and `nap` sit in **one database**, so the UK picker is a single query. Phase 1 needed two steps.

### 2.1 Record version: save this. Counts only.
Save to `docs/results-phase1b/p1b-us-consumers.txt` and `p1b-uk-consumers.txt`.

**US:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30min';
SET LOCAL lock_timeout = '5s';

WITH acl_us AS (               -- COMP-21 US filter, unchanged from FINALIZE B3.1-US / VERIFY P1-US
  SELECT c_consumer_name, c_source_system, c_entity_type, c_entity_value
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('CORE','VAP','PIN')
    AND c_entity_type IN ('Chain','OrgID','SuperChain')
),
consumer AS (
  SELECT c_consumer_name,
         array_agg(DISTINCT c_source_system::text)
           || CASE WHEN bool_or(c_source_system = 'CORE') THEN ARRAY['PIN'] ELSE ARRAY[]::text[] END AS platforms
  FROM acl_us GROUP BY 1
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
         count(*) OVER () AS n
  FROM consumer_cases GROUP BY c_consumer_name
),
picks AS (                      -- small = p10, median = (n+1)/2 (as in B3.1), large = p90, largest = n
  SELECT 'small' AS size_class, 1 AS ord, * FROM ranked WHERE rn = GREATEST(1, ceil(0.10 * n)::int)
  UNION ALL SELECT 'median', 2, * FROM ranked WHERE rn = (n + 1) / 2
  UNION ALL SELECT 'large', 3, * FROM ranked WHERE rn = ceil(0.90 * n)::int
  UNION ALL SELECT 'largest', 4, * FROM ranked WHERE rn = n
),
cols AS (
  SELECT u.c_consumer_name,
         CASE u.c_entity_type WHEN 'SuperChain' THEN 'C.C_LEVEL5_ENTITY' ELSE 'C.C_LEVEL4_ENTITY' END AS col,
         string_agg(DISTINCT quote_literal(u.c_entity_value), ',' ORDER BY quote_literal(u.c_entity_value)) AS vals,
         count(DISTINCT u.c_entity_value) AS n_vals
  FROM acl_us u GROUP BY 1, 2
),
out AS (
  SELECT p.size_class, p.ord, p.n_cases, p.n AS n_ranked_consumers,
         (SELECT sum(g.n_vals) FROM cols g WHERE g.c_consumer_name = p.c_consumer_name) AS n_values,
         pl.platform_list,
         (SELECT '(' || string_agg(g.col || ' IN (' || g.vals || ')', ' OR ' ORDER BY g.col) || ')'
            FROM cols g WHERE g.c_consumer_name = p.c_consumer_name) AS entity_predicate
  FROM picks p JOIN consumer k USING (c_consumer_name)
  CROSS JOIN LATERAL (SELECT string_agg(DISTINCT quote_literal(x), ',' ORDER BY quote_literal(x)) AS platform_list
                      FROM unnest(k.platforms) x) pl
)
SELECT size_class, n_cases, n_values, n_ranked_consumers FROM out ORDER BY ord;
```
**UK:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30min';
SET LOCAL lock_timeout = '5s';

WITH acl_uk AS (               -- COMP-21 UK filter, unchanged from FINALIZE B3.1-UK; load holds wdp.acl and nap in ONE database
  SELECT c_consumer_name, c_source_system, c_entity_type, c_entity_value
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('NAP','LATAM')
    AND c_entity_type IN ('Company ID','Group ID','Outlet ID')
),
plat AS (SELECT DISTINCT c_consumer_name, c_source_system::text AS p FROM acl_uk),
hits AS (
  SELECT a.c_consumer_name, c.i_case_id, c.c_acq_platform FROM acl_uk a JOIN nap."case" c ON c.c_level4_entity = a.c_entity_value
   WHERE a.c_entity_type = 'Company ID' AND c.c_case_sta IN ('OPEN','CLOSED')
  UNION
  SELECT a.c_consumer_name, c.i_case_id, c.c_acq_platform FROM acl_uk a JOIN nap."case" c ON c.c_level5_entity = a.c_entity_value
   WHERE a.c_entity_type = 'Group ID' AND c.c_case_sta IN ('OPEN','CLOSED')
  UNION
  SELECT a.c_consumer_name, c.i_case_id, c.c_acq_platform FROM acl_uk a JOIN nap."case" c ON c.c_level2_entity = a.c_entity_value
   WHERE a.c_entity_type = 'Outlet ID' AND c.c_case_sta IN ('OPEN','CLOSED')
),
scoped AS (
  SELECT h.c_consumer_name, count(DISTINCT h.i_case_id) AS n_cases
  FROM hits h JOIN plat p ON p.c_consumer_name = h.c_consumer_name AND p.p = h.c_acq_platform
  GROUP BY 1
),
ranked AS (
  SELECT c_consumer_name, n_cases, row_number() OVER (ORDER BY n_cases, c_consumer_name) AS rn, count(*) OVER () AS n
  FROM scoped WHERE n_cases > 0
),
picks AS (
  SELECT 'small' AS size_class, 1 AS ord, * FROM ranked WHERE rn = GREATEST(1, ceil(0.10 * n)::int)
  UNION ALL SELECT 'median', 2, * FROM ranked WHERE rn = (n + 1) / 2
  UNION ALL SELECT 'large', 3, * FROM ranked WHERE rn = ceil(0.90 * n)::int
  UNION ALL SELECT 'largest', 4, * FROM ranked WHERE rn = n
),
cols AS (
  SELECT c_consumer_name,
         CASE c_entity_type WHEN 'Company ID' THEN 'C.C_LEVEL4_ENTITY' WHEN 'Group ID' THEN 'C.C_LEVEL5_ENTITY' ELSE 'C.C_LEVEL2_ENTITY' END AS col,
         string_agg(DISTINCT quote_literal(c_entity_value), ',' ORDER BY quote_literal(c_entity_value)) AS vals,
         count(DISTINCT c_entity_value) AS n_vals
  FROM acl_uk GROUP BY 1, 2
),
out AS (
  SELECT p.size_class, p.ord, p.n_cases, p.n AS n_ranked_consumers,
         (SELECT sum(g.n_vals) FROM cols g WHERE g.c_consumer_name = p.c_consumer_name) AS n_values,
         (SELECT string_agg(quote_literal(x.p), ',' ORDER BY x.p) FROM plat x WHERE x.c_consumer_name = p.c_consumer_name) AS platform_list,
         (SELECT '(' || string_agg(g.col || ' IN (' || g.vals || ')', ' OR ' ORDER BY g.col) || ')'
            FROM cols g WHERE g.c_consumer_name = p.c_consumer_name) AS entity_predicate
  FROM picks p
)
SELECT size_class, n_cases, n_values, n_ranked_consumers FROM out ORDER BY ord;
```
### 2.2 Eyes-only version: **do not save into `docs/`, do not paste back**
- It prints `psql_vars`: **8 rows**, one `\set` line per row (`_plat` and `_pred` for each class), for example `\set us_median_plat …` and `\set us_median_pred …`.
- Copy all 8 rows per region (16 lines in total) into **one local file outside the repo**, for example `C:\temp\p1b-vars.psql`. The drivers read it with `\i`.
- The same variables are reused for setup C in Stage 3.
- In pgAdmin, click the `psql_vars` column header to select all 8 rows, press Ctrl+C, and paste into your local vars file.

**US:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30min';
SET LOCAL lock_timeout = '5s';

WITH acl_us AS (               -- COMP-21 US filter, unchanged from FINALIZE B3.1-US / VERIFY P1-US
  SELECT c_consumer_name, c_source_system, c_entity_type, c_entity_value
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('CORE','VAP','PIN')
    AND c_entity_type IN ('Chain','OrgID','SuperChain')
),
consumer AS (
  SELECT c_consumer_name,
         array_agg(DISTINCT c_source_system::text)
           || CASE WHEN bool_or(c_source_system = 'CORE') THEN ARRAY['PIN'] ELSE ARRAY[]::text[] END AS platforms
  FROM acl_us GROUP BY 1
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
         count(*) OVER () AS n
  FROM consumer_cases GROUP BY c_consumer_name
),
picks AS (                      -- small = p10, median = (n+1)/2 (as in B3.1), large = p90, largest = n
  SELECT 'small' AS size_class, 1 AS ord, * FROM ranked WHERE rn = GREATEST(1, ceil(0.10 * n)::int)
  UNION ALL SELECT 'median', 2, * FROM ranked WHERE rn = (n + 1) / 2
  UNION ALL SELECT 'large', 3, * FROM ranked WHERE rn = ceil(0.90 * n)::int
  UNION ALL SELECT 'largest', 4, * FROM ranked WHERE rn = n
),
cols AS (
  SELECT u.c_consumer_name,
         CASE u.c_entity_type WHEN 'SuperChain' THEN 'C.C_LEVEL5_ENTITY' ELSE 'C.C_LEVEL4_ENTITY' END AS col,
         string_agg(DISTINCT quote_literal(u.c_entity_value), ',' ORDER BY quote_literal(u.c_entity_value)) AS vals,
         count(DISTINCT u.c_entity_value) AS n_vals
  FROM acl_us u GROUP BY 1, 2
),
out AS (
  SELECT p.size_class, p.ord, p.n_cases, p.n AS n_ranked_consumers,
         (SELECT sum(g.n_vals) FROM cols g WHERE g.c_consumer_name = p.c_consumer_name) AS n_values,
         pl.platform_list,
         (SELECT '(' || string_agg(g.col || ' IN (' || g.vals || ')', ' OR ' ORDER BY g.col) || ')'
            FROM cols g WHERE g.c_consumer_name = p.c_consumer_name) AS entity_predicate
  FROM picks p JOIN consumer k USING (c_consumer_name)
  CROSS JOIN LATERAL (SELECT string_agg(DISTINCT quote_literal(x), ',' ORDER BY quote_literal(x)) AS platform_list
                      FROM unnest(k.platforms) x) pl
)
SELECT v.line AS psql_vars   -- one \set line per row: 8 rows (4 classes x plat/pred)
FROM out CROSS JOIN LATERAL (VALUES (1, format('\set us_%s_plat %s', out.size_class, quote_literal(out.platform_list))),
                                    (2, format('\set us_%s_pred %s', out.size_class, quote_literal(out.entity_predicate)))) AS v(k, line)
ORDER BY out.ord, v.k;
```
**UK:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30min';
SET LOCAL lock_timeout = '5s';

WITH acl_uk AS (               -- COMP-21 UK filter, unchanged from FINALIZE B3.1-UK; load holds wdp.acl and nap in ONE database
  SELECT c_consumer_name, c_source_system, c_entity_type, c_entity_value
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('NAP','LATAM')
    AND c_entity_type IN ('Company ID','Group ID','Outlet ID')
),
plat AS (SELECT DISTINCT c_consumer_name, c_source_system::text AS p FROM acl_uk),
hits AS (
  SELECT a.c_consumer_name, c.i_case_id, c.c_acq_platform FROM acl_uk a JOIN nap."case" c ON c.c_level4_entity = a.c_entity_value
   WHERE a.c_entity_type = 'Company ID' AND c.c_case_sta IN ('OPEN','CLOSED')
  UNION
  SELECT a.c_consumer_name, c.i_case_id, c.c_acq_platform FROM acl_uk a JOIN nap."case" c ON c.c_level5_entity = a.c_entity_value
   WHERE a.c_entity_type = 'Group ID' AND c.c_case_sta IN ('OPEN','CLOSED')
  UNION
  SELECT a.c_consumer_name, c.i_case_id, c.c_acq_platform FROM acl_uk a JOIN nap."case" c ON c.c_level2_entity = a.c_entity_value
   WHERE a.c_entity_type = 'Outlet ID' AND c.c_case_sta IN ('OPEN','CLOSED')
),
scoped AS (
  SELECT h.c_consumer_name, count(DISTINCT h.i_case_id) AS n_cases
  FROM hits h JOIN plat p ON p.c_consumer_name = h.c_consumer_name AND p.p = h.c_acq_platform
  GROUP BY 1
),
ranked AS (
  SELECT c_consumer_name, n_cases, row_number() OVER (ORDER BY n_cases, c_consumer_name) AS rn, count(*) OVER () AS n
  FROM scoped WHERE n_cases > 0
),
picks AS (
  SELECT 'small' AS size_class, 1 AS ord, * FROM ranked WHERE rn = GREATEST(1, ceil(0.10 * n)::int)
  UNION ALL SELECT 'median', 2, * FROM ranked WHERE rn = (n + 1) / 2
  UNION ALL SELECT 'large', 3, * FROM ranked WHERE rn = ceil(0.90 * n)::int
  UNION ALL SELECT 'largest', 4, * FROM ranked WHERE rn = n
),
cols AS (
  SELECT c_consumer_name,
         CASE c_entity_type WHEN 'Company ID' THEN 'C.C_LEVEL4_ENTITY' WHEN 'Group ID' THEN 'C.C_LEVEL5_ENTITY' ELSE 'C.C_LEVEL2_ENTITY' END AS col,
         string_agg(DISTINCT quote_literal(c_entity_value), ',' ORDER BY quote_literal(c_entity_value)) AS vals,
         count(DISTINCT c_entity_value) AS n_vals
  FROM acl_uk GROUP BY 1, 2
),
out AS (
  SELECT p.size_class, p.ord, p.n_cases, p.n AS n_ranked_consumers,
         (SELECT sum(g.n_vals) FROM cols g WHERE g.c_consumer_name = p.c_consumer_name) AS n_values,
         (SELECT string_agg(quote_literal(x.p), ',' ORDER BY x.p) FROM plat x WHERE x.c_consumer_name = p.c_consumer_name) AS platform_list,
         (SELECT '(' || string_agg(g.col || ' IN (' || g.vals || ')', ' OR ' ORDER BY g.col) || ')'
            FROM cols g WHERE g.c_consumer_name = p.c_consumer_name) AS entity_predicate
  FROM picks p
)
SELECT v.line AS psql_vars   -- one \set line per row: 8 rows (4 classes x plat/pred)
FROM out CROSS JOIN LATERAL (VALUES (1, format('\set uk_%s_plat %s', out.size_class, quote_literal(out.platform_list))),
                                    (2, format('\set uk_%s_pred %s', out.size_class, quote_literal(out.entity_predicate)))) AS v(k, line)
ORDER BY out.ord, v.k;
```
If two classes resolve to the same consumer (possible when few consumers exist), that's fine: the cells simply repeat. The record version shows it.

---

## 3. Test matrix and what is already measured

**Per region:** 4 classes × 5 ranges × {count, page 1} = **40 cells**, plus **2 deep pages** (large and largest, 60 d), so **42 plans per setup.** On top of that, per region and setup: 4 noise sets (§7) and 2 cold runs (§5).

**Deep-page offset** = `(⌊total / 200⌋ − 1) × 200`, from the old count for that consumer: the start of the last full page. If the total is under 400, the deep page is "n/a". The driver computes this and writes `# OFFSET=` at the top of the file.

### 3.1 Setup A cells measured in Phase 1 (old SQL, no Stage 2 indexes)
`docs/results-phase1/load/b3-{us,uk}-{median,largest}-{2d,60d}-{count,page}.txt`: **8 cells per region** (median and largest × 2 d and 60 d × count and page).

| Region | Missing for setup A |
|---|---|
| US | all small and large cells (20); median and largest × {1, 7, 30 d} × {count, page} (12); 2 deep pages = **34** |
| UK | same = **34** |

- **These 8 cells per region can't count as setup A after step 1.** They were measured **before** the VACUUM, at 22.5 % (US `action`) and 0.0 % (UK `action`) all-visible, so their plans and timings aren't comparable with A after the VACUUM.
- **Recommendation: re-measure all 42 per region in setup A** (the drivers do). Keep the Phase 1 files as a "before VACUUM" reference only.
- This differs from the brief, which asks only for the missing cells.

---

## 4. Setting the Stage 2 indexes aside (step 4) and restoring them (step 6)

### X. Set aside, LOAD only
```sql
-- LOAD only. Auto-commit on, one statement at a time.
SET statement_timeout = 0;
SET lock_timeout = '5s';
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_case_level4_entity_scope;
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_case_level5_entity_scope;
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_action_case_processed_est_sta;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_case_level4_entity_scope;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_case_level5_entity_scope;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_action_case_processed_sta;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_action_processed_actionid_sta;
```
**X-check** (save to `p1b-index-setaside.txt`): all 7 rows `present = false`.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT x.schema_name, x.index_name,
       (i.indexrelid IS NOT NULL) AS present, i.indisvalid, i.indisready,
       pg_size_pretty(pg_relation_size(i.indexrelid)) AS size
FROM (VALUES ('wdp','ix_case_level4_entity_scope'), ('wdp','ix_case_level5_entity_scope'), ('wdp','ix_action_case_processed_est_sta'), ('nap','ix_case_level4_entity_scope'), ('nap','ix_case_level5_entity_scope'), ('nap','ix_action_case_processed_sta'), ('nap','ix_action_processed_actionid_sta')) AS x(schema_name, index_name)
LEFT JOIN pg_namespace n ON n.nspname = x.schema_name
LEFT JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = x.index_name
LEFT JOIN pg_index i ON i.indexrelid = c.oid
ORDER BY 1, 2;
```
### R. Restore, LOAD only
- These are the exact Stage 2 definitions. Build time is minutes per index; US `ix_action_case_processed_est_sta` is the largest, about 1.3 GB.
- If a build fails, it leaves an INVALID index. Run the matching `DROP INDEX CONCURRENTLY IF EXISTS` from X, then retry that one statement.
```sql
-- LOAD only. Auto-commit on, one statement at a time. Same definitions as GDP-ACTIVITIES-PHASE1-INDEXES.md US-1..UK-7.
SET statement_timeout = 0;
SET lock_timeout = '5s';
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level4_entity_scope ON wdp."case" (c_level4_entity, c_acq_platform, c_case_sta) INCLUDE (i_case_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level5_entity_scope ON wdp."case" (c_level5_entity, c_acq_platform, c_case_sta) INCLUDE (i_case_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_case_processed_est_sta ON wdp.action (i_case_id, d_action_processed_est, c_action_sta) INCLUDE (i_action_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level4_entity_scope ON nap."case" (c_level4_entity, c_acq_platform, c_case_sta) INCLUDE (i_case_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level5_entity_scope ON nap."case" (c_level5_entity, c_acq_platform, c_case_sta) INCLUDE (i_case_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_case_processed_sta ON nap."action" (i_case_id, d_action_processed, c_action_sta) INCLUDE (i_action_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_processed_actionid_sta ON nap."action" (d_action_processed, i_action_id, c_action_sta) INCLUDE (i_case_id);
```
**R-check** (save to `p1b-index-restore.txt`): run the same query as X-check. All 7 rows should show `present = true`, `indisvalid = true` and `indisready = true`.
---

## 5. Cold runs (one per region per setup: largest, 60 d, count and page)

Scripts: `docs/phase1b-scripts/p1b-us-cold-old.psql` and `p1b-uk-cold-old.psql`.
- Run them **before** the matching driver in each setup, so the region's pages haven't just been read.
- Each script evicts the region's tables and indexes from `shared_buffers` with `pg_buffercache_evict()`, then runs the block once.

**Requirements (load only):**
- the extension: `CREATE EXTENSION IF NOT EXISTS pg_buffercache;`
- a **superuser** connection.

If either is missing, the file says `# EVICTION FAILED - this is NOT a cold run`, and the run counts as warm. The alternative is a load restart by the DBA right before the script. Otherwise, record "cold run not possible".

Neither method empties the operating system's file cache. A "cold" run here means **PostgreSQL buffers cold**. Disk reads show up as `read=` with `I/O Timings`.

```
\i C:/temp/p1b-vars.psql
\set setup a
\set outdir 'C:/Users/rsing/workspace-wdp-new/gdp-search/images/p1b'
\i C:/Users/rsing/workspace-wdp-new/gdp-search/docs/phase1b-scripts/p1b-us-cold-old.psql
\i C:/Users/rsing/workspace-wdp-new/gdp-search/docs/phase1b-scripts/p1b-uk-cold-old.psql
```

---

## 6. Setups A and B: the matrix (steps 5 and 7)

### 6.1 With the drivers (recommended)
```
\i C:/temp/p1b-vars.psql
\set setup a                -- step 7: \set setup b
\set outdir 'C:/Users/rsing/workspace-wdp-new/gdp-search/images/p1b'
\i C:/Users/rsing/workspace-wdp-new/gdp-search/docs/phase1b-scripts/p1b-us-old.psql
\i C:/Users/rsing/workspace-wdp-new/gdp-search/docs/phase1b-scripts/p1b-uk-old.psql
```
- Each driver prints the name of each cell as it runs.
- A timed-out cell (10 min) logs an error and the driver carries on; the file then holds the error text.
- `_warmup.txt` is scratch and is overwritten each time.
- Expected time: about the sum of the old-SQL times × 2, a few minutes per region for setup A.

### 6.2 What a driver writes (per region, per setup)
| Files | Count |
|---|---|
| `<setup>-<region>-<class>-<n>d-<count\|page>.txt` for class ∈ {small, median, large, largest}, n ∈ {1, 2, 7, 30, 60} | 40 |
| `<setup>-<region>-{large,largest}-60d-page-deep.txt` (first lines: cell and `# OFFSET=`, or "n/a") | 2 |
| `<setup>-<region>-noise-{median-7d-count, median-7d-page, largest-60d-count, largest-60d-page}.txt` (5 warm runs each) | 4 |

### 6.3 Manual templates (pgAdmin), if not using the drivers
Placeholders:
- `<PLATFORM_LIST>` and `<ENTITY_PREDICATE>` from the eyes-only picker (`platform_list` / `entity_predicate`);
- `<FROM_DATE>` / `<TO_DATE>` from §0;
- `<OFFSET>` = `0`, or the deep-page offset from §3.

Run twice and keep the second.

**US count:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH LATEST_ACTIONS AS ( SELECT C.I_CASE as caseNumber
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE> )
SELECT count(*)
  FROM LATEST_ACTIONS
  LIMIT 1 ;
```
**US page:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  ORDER BY actionProcessedESTDate, actionId ASC
  LIMIT 200 OFFSET <OFFSET>;
```
**UK count:**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH LATEST_ACTIONS AS ( SELECT C.I_CASE as caseNumber
  FROM nap."case" c
  INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE> )
SELECT count(*)
  FROM LATEST_ACTIONS
  LIMIT 1 ;
```
**UK page** (load: customer columns removed):
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, /* load only: C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, -- absent in load nap.case */ /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM nap."case" c
  INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  ORDER BY actionProcessedDate, actionId ASC
  LIMIT 200 OFFSET <OFFSET>;
```
---

## 7. Noise runs and the margin for the proof bar

**What is measured:** each driver re-runs 4 cells 5 times, warm, in one transaction: median 7 d count, median 7 d page, largest 60 d count, largest 60 d page. That's per region and setup, so 16 sets of 5 by the end of setup B. Setup C adds 8 more in Stage 3.

**Proposed rule (decision F5).** A cell **passes** if
> C ≤ A × (1 + m) + 5 ms

- **m** = the largest relative spread, (max − min) / median, seen in any 5-run noise set, rounded up to the next 5 %.
- **The 5 ms floor** covers cells under about 50 ms, where timer and plan-startup jitter dominate a percentage.

**Why this rule:**
- It uses noise measured in the same environment and session conditions as the cells.
- It takes the worst case seen, so a pass isn't decided by a lucky run.
- It's stated before C is measured.

The final m (with numbers) goes in the Stage 3 report. If m turns out above 25 %, I'll say so and propose re-running the affected cells three times, using their median.

---

## 8. Checklist

### 8.1 You save (counts only, straight into `docs/results-phase1b/`)
| ☐ | File | From |
|---|---|---|
| ☐ | `p1b-visibility.txt` | M2, after M1 |
| ☐ | `p1b-us-consumers.txt`, `p1b-uk-consumers.txt` | §2.1 |
| ☐ | `p1b-index-setaside.txt` | X-check (step 4) |
| ☐ | `p1b-index-restore.txt` | R-check (step 6) |

### 8.2 Raw, to `images/p1b/` (I redact into `docs/results-phase1b/`)
| ☐ | Files | Per setup |
|---|---|---|
| ☐ | `a-us-*.txt`, `a-uk-*.txt`: 40 cells + 2 deep + 4 noise per region | 92 |
| ☐ | `a-us-largest-60d-{count,page}-cold.txt`, `a-uk-largest-60d-{count,page}-cold.txt` | 4 |
| ☐ | `b-…`: the same for setup B | 96 |

**Not saved anywhere, by design:** the eyes-only picker output and your local vars file.

---

## 9. Differences from the brief

1. **Raw plans** go to `images/p1b/`, not `docs/results-phase1b/`, because they contain entity values; I redact them into `docs/results-phase1b/` (§0).
2. **Setup A is re-measured for all cells,** not only the missing ones, because the existing 8 per region pre-date the VACUUM (§3.1).
3. **"Cold" means PostgreSQL buffers cold,** not the OS cache, and it needs `pg_buffercache` plus a superuser in load, or a DBA restart (§5).
4. **60 d is 61 calendar days** (`DAYS.between = 60`), as in Phase 1 (§0).

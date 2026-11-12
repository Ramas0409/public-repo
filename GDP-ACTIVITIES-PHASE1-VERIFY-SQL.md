# GDP-ACTIVITIES-PHASE1-VERIFY-SQL.md
**Phase 1 verification pack: old vs new equivalence (Part 1) and new-path plans (Part 2), US and UK**
*Prepared 2026-11-11 (Stage 4). Nothing here has been run against US or UK. Companions: `GDP-ACTIVITIES-PHASE1-INDEXES.md` (Stage 2), `GDP-ACTIVITIES-PHASE1-IMPLEMENTATION.md` (Stage 3), `GDP-ACTIVITIES-FINALIZE-SQL.md` (baseline pack, cited as "FINALIZE").*

> **Part 2 is only meaningful after the Stage 2 indexes exist** (built on the writer, replicated, and shown `indisvalid = t` on the replica you use; block P0). Run before the indexes, the new SQL is still correct, but its plans and timings say nothing about Phase 1.
>
> Part 1 checks correctness and can run at any time. Its run times are not measurements.

---

## 0. How to run

**Read-only, as in FINALIZE.** Every block is a `SELECT`, an `EXPLAIN (ANALYZE, …)` of a `SELECT`, or a catalog read. There is no DDL and no DML. Each block opens its own read-only transaction:
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '…';   -- set per block
SET LOCAL lock_timeout = '5s';
```
- Run `ROLLBACK;` after each block, and also after an error or timeout.
- **Run on the read replicas** (P0 records `pg_is_in_recovery() = true`). Phase 1 moves activities to the replicas (D2).
- On a replica, a long statement can be cancelled with `canceling statement due to conflict with recovery`. If that happens, run it again.

**pgAdmin:** one statement per sub-block. Copy the whole Data Output grid with headers, or use *Save results to file*. Append it under the heading named in the tables below.

**Result files** go in `docs/results-phase1/` (create the folder). Do not commit or stage anything there.

### 0.1 Where to run (decided 2026-11-11)

The **load** environment is the main one: prod data copy, same indexes as prod, and new indexes can be built there. **Prod** runs only blocks that do not need the new indexes. Keep the two environments apart: save load results in `docs/results-phase1/load/` and prod results in `docs/results-phase1/prod/`, with the same file names as in §3.

| Step | Env | What | Saves to |
|---|---|---|---|
| L1 | load | FINALIZE B1.0, B1.1, B1.2 (settings, table sizes / all-visible share, index inventory), to confirm load matches prod (B1.2 vs `docs/results/b1-*.txt`) | `results-phase1/load/l1-<us\|uk>-inventory.txt` |
| L2 | load, **before** the Stage 2 indexes | FINALIZE B3.1 picker, then the 16 B3.2 baseline plans (old SQL) | `results-phase1/load/b3-*.txt` (same names as `docs/results/`) |
| L3 | load | build the Stage 2 indexes (`GDP-ACTIVITIES-PHASE1-INDEXES.md`) | — |
| L4 | load | this pack: P0, P1, Part 1, 2.0, Part 2; plus the optional old-path plans (2.2), to separate the index effect from the new-path effect | `results-phase1/load/p1-*.txt` |
| P1 | prod replica | P1 picker, Part 1 (equivalence on real prod data), 2.0 size check. No new index is needed for these | `results-phase1/prod/p1-*-equiv.txt`, `p1-*-sizecheck.txt` |
| P2 | prod replica, later | Part 2, only after the DBA builds the Stage 2 indexes in prod | `results-phase1/prod/p1-*.txt` |

Notes:
- **If load has no read replica:** run on the load writer, and record `pg_is_in_recovery = false` in the file header. P0 will then show `is_replica = f`, which is expected.
- **Before/after comparisons stay within one environment.** The report compares load L2 (before) with load L4 (after). Prod B3 (`docs/results/`) is then context only.
- **Load can differ from prod** even with the same data: `pg_stats`, the all-visible share, cache size and hardware. L1 records the first two, and the report states them next to each number.
- **Load dates (from FINALIZE B1.2b, 2026-11-12).** The pickers' `to_date` / `from_date_*` (based on `current_date`) do **not** apply in load.
  - **US load data ends on 2026-10-06**, a partial day (inserts until about 06:35 UTC). In load, use `<TO_DATE>` = `2026-10-05`, 2-day `<FROM_DATE>` = `2026-10-04` and 60-day `<FROM_DATE>` = `2026-08-06`, both in L2 (B3.2) and in L4.
  - **UK load has only 2 actions since 2026-05-01.** The UK window is set from FINALIZE B1.2c: pick the last complete day of a month with normal volume.
  - The `cap_*` values do not change, because they depend only on the number of days.

**Where the SQL comes from.** The SQL here was **generated from the code**, not typed by hand:
- the old path is produced by the unchanged shared builder (`USDisputeCaseSearchDAOImpl` / `UKDisputeCaseSearchDAOImpl.buildDynamicSearchQuery`, as the activities endpoint calls it);
- the new path is produced by `ActivitiesSqlBuilder`;
- bound parameters were then inlined as literals or `<PLACEHOLDERS>`.

Each block reproduces the default activities request, as in the B3 baseline: entity scope + platform + activity date range, default action and case status, page size 200, `maxAction=false`.

**Local check of this pack.** Every block was executed on local PostgreSQL 17.10, in throwaway schemas `p1cmp_wdp` / `p1cmp_nap` (production column shapes from B1.6, Stage 2 indexes, synthetic data). The schemas were dropped afterwards.
- Covered: pickers, size checks, E1, E2 at OFFSET 0 / 200 / 100000, maxAction E1/E2, and all EXPLAIN blocks, for 2 consumers × 2 ranges × 2 regions: **96 blocks, 0 errors, 0 mismatches**.
- Negative test: an E2 run whose new side was shifted by one row returned `f` for every match column. The check does detect differences.
- UK page SQL has `C.T_CUST_DSCRTN_3 as customerField3` commented out, in old and new alike, as in B3. That column is absent in UK production (report §7). The UK page you measure is therefore the app's SQL minus that one column.

### Placeholders

| Placeholder | Filled from | Example form |
|---|---|---|
| `<PLATFORM_LIST>` | picker `platform_list` | `'CORE','PIN','VAP'` |
| `<ENTITY_PREDICATE>` | picker `entity_predicate` (OR form; used by the old SQL, the date-first SQL and the size check) | `(C.C_LEVEL4_ENTITY IN ('…') OR C.C_LEVEL5_ENTITY IN ('…'))` |
| `<IN_SCOPE_CASES_BRANCHES>` | picker `in_scope_cases_branches` (cases-first SQL; one UNION branch per entity column, platform list already inside) | `SELECT c.i_case_id FROM wdp."case" c WHERE … UNION SELECT …` |
| `<FROM_DATE>` / `<TO_DATE>` | picker `from_date_2d` or `from_date_60d` / `to_date` | `2026-11-09` |
| `<CAP>` | picker `cap_2d` or `cap_60d` (default daily figure × days; if `activities.<region>.avg-actions-per-day` is configured differently, use that figure × 2 or × 61) | `97400` |
| `<OFFSET>` | `0` for page 1; for the deep page see §1.2 | `0` |

**Redaction (same rule as FINALIZE B3.2):**
- Picker output is **eyes only**: never save it, never paste it back.
- Part 1 and the size checks return **counts and true/false only**, so their output can be saved as is.
- Plans (Part 2) contain entity values in `Index Cond` / `Filter` / `Recheck Cond` / `Hash Cond` lines. Replace each value with `<V1>`, `<V2>`, …, using the same token for the same value. A long array literal can become `'{<V1>,…,<Vn>}'`.
- Dates and platform codes may stay.
- Search the file for every picker value before saving.
- At the top of each saved file, add one header line: run date and time, replica name or "replica", and `n_values` / `n_cases`. Those are counts.

---

## P0. Prerequisites: replica, and the Stage 2 indexes present and valid

Save to `docs/results-phase1/p1-us-prereq.txt` and `p1-uk-prereq.txt`. **Run time class:** light.
- Expect `is_replica = t`, and five rows with `indisvalid = t` and `indisready = t`: the Stage 2 indexes (US 3, UK 4), plus the existing indexes the new SQL also uses (US `ix_action_processed_est_actionid_case` and `ix_case_mid`; UK `ix_case_mid`).
- If a new index is missing, or shows `f`, stop: Part 2 is not meaningful yet.

#### P0-US (run on the US replica)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT pg_is_in_recovery() AS is_replica, now() AS run_at, current_setting('server_version') AS version,
       n.nspname, c.relname, i.indisvalid, i.indisready, pg_size_pretty(pg_relation_size(i.indexrelid)) AS size,
       pg_get_indexdef(i.indexrelid) AS definition
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'wdp' AND c.relname IN ('ix_case_level4_entity_scope','ix_case_level5_entity_scope','ix_action_case_processed_est_sta','ix_action_processed_est_actionid_case','ix_case_mid')
ORDER BY c.relname;
```
#### P0-UK (run on the UK replica)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT pg_is_in_recovery() AS is_replica, now() AS run_at, current_setting('server_version') AS version,
       n.nspname, c.relname, i.indisvalid, i.indisready, pg_size_pretty(pg_relation_size(i.indexrelid)) AS size,
       pg_get_indexdef(i.indexrelid) AS definition
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'nap' AND c.relname IN ('ix_case_level4_entity_scope','ix_case_level5_entity_scope','ix_action_case_processed_sta','ix_action_processed_actionid_sta','ix_case_mid')
ORDER BY c.relname;
```
---

## P1. Consumer picker (for your eyes only)

> **DO NOT SAVE the picker output. Do not paste it back to me.** It prints real entity values and, for US, the consumer name. Use it only to fill the placeholders on your screen.

These are the same consumers as the baseline: FINALIZE B3.1's ranking, logic unchanged (median = rank ⌈n/2⌉, largest = rank n, by in-scope case count). Only the final `SELECT` adds new output:
- `in_scope_cases_branches`;
- `cap_2d` and `cap_60d`;
- a de-duplicated platform list;
- entity values merged per case column, as the new path does. That gives the same set of cases as B3.1's per-type terms.

If the ranking picks a different consumer than in B3 (data has moved since 2026-11-11), note it in the file header. The report will then compare against B3 with that caveat.

#### P1-US (run on US)
**Run time class:** heavy, about 1.5 min (same joins as B3.1-US).
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30min';
SET LOCAL lock_timeout = '5s';

WITH acl_us AS (               -- COMP-21 US filter (unchanged from FINALIZE-SQL B3.1-US)
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
),
cols AS (                      -- one row per (consumer, case column): values merged per column, as the new path does
  SELECT u.c_consumer_name,
         CASE u.c_entity_type WHEN 'SuperChain' THEN 'C.C_LEVEL5_ENTITY' ELSE 'C.C_LEVEL4_ENTITY' END AS col,
         string_agg(DISTINCT quote_literal(u.c_entity_value), ',' ORDER BY quote_literal(u.c_entity_value)) AS vals,
         count(DISTINCT u.c_entity_value) AS n_vals
  FROM acl_us u
  GROUP BY 1, 2
)
SELECT p.pick, p.c_consumer_name, p.n_cases, p.n AS n_ranked_consumers,
       pl.platform_list,
       (SELECT '(' || string_agg(g.col || ' IN (' || g.vals || ')', ' OR ' ORDER BY g.col) || ')'
          FROM cols g WHERE g.c_consumer_name = p.c_consumer_name)                               AS entity_predicate,
       (SELECT string_agg(format('SELECT c.i_case_id FROM wdp."case" c WHERE C.C_ACQ_PLATFORM IN (%s) AND C.c_case_sta IN (''OPEN'',''CLOSED'') AND %s IN (%s)',
                                 pl.platform_list, g.col, g.vals), ' UNION ' ORDER BY g.col)
          FROM cols g WHERE g.c_consumer_name = p.c_consumer_name)                               AS in_scope_cases_branches,
       (SELECT sum(g.n_vals) FROM cols g WHERE g.c_consumer_name = p.c_consumer_name)            AS n_values,
       to_char(current_date - 1,  'YYYY-MM-DD') AS to_date,
       to_char(current_date - 2,  'YYYY-MM-DD') AS from_date_2d,
       to_char(current_date - 61, 'YYYY-MM-DD') AS from_date_60d,
       48700 * 2  AS cap_2d,     -- activities.us.avg-actions-per-day (default 48700) x 2 days
       48700 * 61 AS cap_60d     -- x 61 days (from_date_60d .. to_date inclusive)
FROM picks p JOIN consumer k USING (c_consumer_name)
CROSS JOIN LATERAL (SELECT string_agg(DISTINCT quote_literal(x), ',' ORDER BY quote_literal(x)) AS platform_list FROM unnest(k.platforms) x) pl
ORDER BY p.pick DESC;
```
#### P1-UK, step 1 (run on **US**): generate the UK picker
The ACL is in the US database, so this step prints one SQL text cell (`uk_picker_sql`), as in FINALIZE B3.1-UK step 1. **Run time class:** light.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '60s';
SET LOCAL lock_timeout = '5s';

WITH acl_uk AS (               -- COMP-21 UK filter (unchanged from FINALIZE-SQL B3.1-UK)
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
),
cols AS (
  SELECT k, CASE code WHEN 'CO' THEN 'C.C_LEVEL4_ENTITY' WHEN 'GR' THEN 'C.C_LEVEL5_ENTITY' ELSE 'C.C_LEVEL2_ENTITY' END AS col,
         string_agg(DISTINCT quote_literal(val), ',' ORDER BY quote_literal(val)) AS vals,
         count(DISTINCT val) AS n_vals
  FROM acl GROUP BY 1, 2
)
SELECT pk.pick, pk.k AS consumer_key, pk.n_cases, pk.n AS n_ranked_consumers,
       pl.platform_list,
       (SELECT '(' || string_agg(g.col || ' IN (' || g.vals || ')', ' OR ' ORDER BY g.col) || ')'
          FROM cols g WHERE g.k = pk.k)                                                          AS entity_predicate,
       (SELECT string_agg(format('SELECT c.i_case_id FROM nap."case" c WHERE C.C_ACQ_PLATFORM IN (%s) AND C.c_case_sta IN (''OPEN'',''CLOSED'') AND %s IN (%s)',
                                 pl.platform_list, g.col, g.vals), ' UNION ' ORDER BY g.col)
          FROM cols g WHERE g.k = pk.k)                                                          AS in_scope_cases_branches,
       (SELECT sum(g.n_vals) FROM cols g WHERE g.k = pk.k)                                       AS n_values,
       to_char(current_date - 1,  'YYYY-MM-DD') AS to_date,
       to_char(current_date - 2,  'YYYY-MM-DD') AS from_date_2d,
       to_char(current_date - 61, 'YYYY-MM-DD') AS from_date_60d,
       25600 * 2  AS cap_2d,     -- activities.uk.avg-actions-per-day (default 25600) x 2 days
       25600 * 61 AS cap_60d
FROM picks pk
CROSS JOIN LATERAL (SELECT string_agg(quote_literal(p), ',' ORDER BY p) AS platform_list FROM plat WHERE plat.k = pk.k) pl
ORDER BY pk.pick DESC;
$tail$ AS uk_picker_sql;
```
#### P1-UK, step 2 (run on the **UK** replica)
**Run time class:** medium.
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15min';
SET LOCAL lock_timeout = '5s';

-- paste the uk_picker_sql cell from step 1 here
```

---

## Part 1. Equivalence: old path vs new path (match / mismatch and counts only)

What each block returns:
- **E1 (count):** the old total, the new total with the cases-first start, the new total with the date-first start, and `totals_match`.
  - Both new starts are checked on every request, whichever one the size check would choose.
- **E2 (page):** row counts of the old page and of both new pages, plus four flags.
  - `keys_match_*`: the same action ids in the same order (order key = activity date, then action id).
  - `rows_match_*`: every column of every row identical (md5 of each whole row, in that order).
  - **No row data is returned.**
- Every E1/E2 runs all three variants (old, new cases-first, new date-first) in one statement and one snapshot. Its run time is the sum of the three, so it is not a measurement.

Save every Part 1 output, one heading per run (for example `### E1 median 2d`), into `docs/results-phase1/p1-us-equiv.txt` and `p1-uk-equiv.txt`.

### 1.1 E1: count equivalence
**Run time class:** US light–heavy (the old count for the largest 60-day consumer was 7.6 s on the writer, B3 #7); UK light–medium.

#### E1-US (run on the US replica)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

SELECT o.n AS old_total, cf.n AS new_total_cases_first, df.n AS new_total_date_first,
       (o.n = cf.n AND o.n = df.n) AS totals_match
FROM (
WITH LATEST_ACTIONS AS ( SELECT C.I_CASE as caseNumber
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE> )
SELECT count(*)
  FROM LATEST_ACTIONS
  LIMIT 1
) AS o(n),
(
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>)
SELECT count(*)
  FROM in_scope_cases isc
  JOIN wdp.action a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
) AS cf(n),
(
SELECT count(*)
  FROM wdp.action a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM wdp."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
) AS df(n);
```
#### E1-UK (run on the UK replica)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

SELECT o.n AS old_total, cf.n AS new_total_cases_first, df.n AS new_total_date_first,
       (o.n = cf.n AND o.n = df.n) AS totals_match
FROM (
WITH LATEST_ACTIONS AS ( SELECT C.I_CASE as caseNumber
  FROM nap."case" c
  INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE> )
SELECT count(*)
  FROM LATEST_ACTIONS
  LIMIT 1
) AS o(n),
(
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>)
SELECT count(*)
  FROM in_scope_cases isc
  JOIN nap."action" a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
) AS cf(n),
(
SELECT count(*)
  FROM nap."action" a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM nap."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
) AS df(n);
```
### 1.2 E2: page equivalence
`<OFFSET>`:
- `0` for page 1.
- For the **deep page** (largest consumer, 60 days only): take `old_total` from that consumer's E1 and use **OFFSET = (⌊old_total / 200⌋ − 1) × 200**. That is the start of the last full page.
  - If `old_total < 400`, there is no deep page; write `deep page: n/a (old_total < 400)` in the file.
  - Use the same OFFSET for the deep-page EXPLAIN in Part 2.

**Run time class:** light–medium (old US page ≤ 0.75 s and old UK page ≤ 1.6 s in B3; the deep page reads more).

#### E2-US (run on the US replica)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

WITH old_page AS MATERIALIZED (
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  ORDER BY actionProcessedESTDate, actionId ASC
  LIMIT 200 OFFSET <OFFSET>
),
new_cf AS MATERIALIZED (
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>),
page_keys AS MATERIALIZED (SELECT a.i_action_id
  FROM in_scope_cases isc
  JOIN wdp.action a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  ORDER BY a.d_action_processed_est, a.i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN wdp.action a ON a.i_action_id = pk.i_action_id
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedESTDate, actionId ASC
),
new_df AS MATERIALIZED (
WITH page_keys AS MATERIALIZED (SELECT a.i_action_id
  FROM wdp.action a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM wdp."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
  ORDER BY a.d_action_processed_est, a.i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN wdp.action a ON a.i_action_id = pk.i_action_id
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedESTDate, actionId ASC
)
SELECT (SELECT count(*) FROM old_page) AS old_rows,
       (SELECT count(*) FROM new_cf)   AS new_rows_cases_first,
       (SELECT count(*) FROM new_df)   AS new_rows_date_first,
       (SELECT array_agg(t.actionid ORDER BY t.actionprocessedestdate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT array_agg(t.actionid ORDER BY t.actionprocessedestdate, t.actionid) FROM new_cf t) AS keys_match_cases_first,
       (SELECT array_agg(t.actionid ORDER BY t.actionprocessedestdate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT array_agg(t.actionid ORDER BY t.actionprocessedestdate, t.actionid) FROM new_df t) AS keys_match_date_first,
       (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocessedestdate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocessedestdate, t.actionid) FROM new_cf t) AS rows_match_cases_first,
       (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocessedestdate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocessedestdate, t.actionid) FROM new_df t) AS rows_match_date_first;
```
#### E2-UK (run on the UK replica)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

WITH old_page AS MATERIALIZED (
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM nap."case" c
  INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  ORDER BY actionProcessedDate, actionId ASC
  LIMIT 200 OFFSET <OFFSET>
),
new_cf AS MATERIALIZED (
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>),
page_keys AS MATERIALIZED (SELECT a.i_action_id
  FROM in_scope_cases isc
  JOIN nap."action" a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  ORDER BY a.d_action_processed, a.i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN nap."action" a ON a.i_action_id = pk.i_action_id
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedDate, actionId ASC
),
new_df AS MATERIALIZED (
WITH page_keys AS MATERIALIZED (SELECT a.i_action_id
  FROM nap."action" a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM nap."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
  ORDER BY a.d_action_processed, a.i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN nap."action" a ON a.i_action_id = pk.i_action_id
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedDate, actionId ASC
)
SELECT (SELECT count(*) FROM old_page) AS old_rows,
       (SELECT count(*) FROM new_cf)   AS new_rows_cases_first,
       (SELECT count(*) FROM new_df)   AS new_rows_date_first,
       (SELECT array_agg(t.actionid ORDER BY t.actionprocesseddate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT array_agg(t.actionid ORDER BY t.actionprocesseddate, t.actionid) FROM new_cf t) AS keys_match_cases_first,
       (SELECT array_agg(t.actionid ORDER BY t.actionprocesseddate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT array_agg(t.actionid ORDER BY t.actionprocesseddate, t.actionid) FROM new_df t) AS keys_match_date_first,
       (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocesseddate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocesseddate, t.actionid) FROM new_cf t) AS rows_match_cases_first,
       (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocesseddate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocesseddate, t.actionid) FROM new_df t) AS rows_match_date_first;
```
### 1.3 maxAction = true (largest consumer, 60 days, page 1 only)
COMP-21 activities never sets `maxAction` (it is set to true only on its search-all path: `USCaseDetailsServiceImpl:98`, `UKCaseDetailsServiceImpl:102`). COMP-27 still serves `maxAction=true` on this endpoint, so it is checked once per region. Old SQL: `DISTINCT ON (C.I_CASE_ID) … ORDER BY C.I_CASE_ID, A.I_ACTION_SEQ::INT4 DESC`. New SQL: the same rule, with the count as `count(DISTINCT a.i_case_id)`.

#### E1-MAX-US
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

SELECT o.n AS old_total, cf.n AS new_total_cases_first, df.n AS new_total_date_first,
       (o.n = cf.n AND o.n = df.n) AS totals_match
FROM (
WITH LATEST_ACTIONS AS ( SELECT DISTINCT ON (C.I_CASE_ID) C.I_CASE as caseNumber
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  ORDER BY C.I_CASE_ID, A.I_ACTION_SEQ::INT4 DESC )
SELECT count(*)
  FROM LATEST_ACTIONS
  LIMIT 1
) AS o(n),
(
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>)
SELECT count(DISTINCT a.i_case_id)
  FROM in_scope_cases isc
  JOIN wdp.action a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
) AS cf(n),
(
SELECT count(DISTINCT a.i_case_id)
  FROM wdp.action a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM wdp."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
) AS df(n);
```
#### E2-MAX-US (`<OFFSET>` = 0)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

WITH old_page AS MATERIALIZED (
WITH LATEST_ACTIONS AS ( SELECT DISTINCT ON (C.I_CASE_ID) C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM wdp.case c
  INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED_EST BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  ORDER BY C.I_CASE_ID, A.I_ACTION_SEQ::INT4 DESC )
SELECT *
  FROM latest_actions
  ORDER BY actionProcessedESTDate, actionId ASC
  LIMIT 200 OFFSET <OFFSET>
),
new_cf AS MATERIALIZED (
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>),
latest_actions AS (SELECT DISTINCT ON (a.i_case_id) a.i_action_id, a.d_action_processed_est AS activity_date
  FROM in_scope_cases isc
  JOIN wdp.action a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  ORDER BY a.i_case_id, a.I_ACTION_SEQ::INT4 DESC),
page_keys AS MATERIALIZED (SELECT i_action_id
  FROM latest_actions
  ORDER BY activity_date, i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN wdp.action a ON a.i_action_id = pk.i_action_id
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedESTDate, actionId ASC
),
new_df AS MATERIALIZED (
WITH latest_actions AS (SELECT DISTINCT ON (a.i_case_id) a.i_action_id, a.d_action_processed_est AS activity_date
  FROM wdp.action a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM wdp."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
  ORDER BY a.i_case_id, a.I_ACTION_SEQ::INT4 DESC),
page_keys AS MATERIALIZED (SELECT i_action_id
  FROM latest_actions
  ORDER BY activity_date, i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN wdp.action a ON a.i_action_id = pk.i_action_id
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedESTDate, actionId ASC
)
SELECT (SELECT count(*) FROM old_page) AS old_rows,
       (SELECT count(*) FROM new_cf)   AS new_rows_cases_first,
       (SELECT count(*) FROM new_df)   AS new_rows_date_first,
       (SELECT array_agg(t.actionid ORDER BY t.actionprocessedestdate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT array_agg(t.actionid ORDER BY t.actionprocessedestdate, t.actionid) FROM new_cf t) AS keys_match_cases_first,
       (SELECT array_agg(t.actionid ORDER BY t.actionprocessedestdate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT array_agg(t.actionid ORDER BY t.actionprocessedestdate, t.actionid) FROM new_df t) AS keys_match_date_first,
       (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocessedestdate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocessedestdate, t.actionid) FROM new_cf t) AS rows_match_cases_first,
       (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocessedestdate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocessedestdate, t.actionid) FROM new_df t) AS rows_match_date_first;
```
#### E1-MAX-UK
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

SELECT o.n AS old_total, cf.n AS new_total_cases_first, df.n AS new_total_date_first,
       (o.n = cf.n AND o.n = df.n) AS totals_match
FROM (
WITH LATEST_ACTIONS AS ( SELECT DISTINCT ON (C.I_CASE_ID) C.I_CASE as caseNumber
  FROM nap."case" c
  INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  ORDER BY C.I_CASE_ID, A.I_ACTION_SEQ::INT4 DESC )
SELECT count(*)
  FROM LATEST_ACTIONS
  LIMIT 1
) AS o(n),
(
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>)
SELECT count(DISTINCT a.i_case_id)
  FROM in_scope_cases isc
  JOIN nap."action" a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
) AS cf(n),
(
SELECT count(DISTINCT a.i_case_id)
  FROM nap."action" a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM nap."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
) AS df(n);
```
#### E2-MAX-UK (`<OFFSET>` = 0)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

WITH old_page AS MATERIALIZED (
WITH LATEST_ACTIONS AS ( SELECT DISTINCT ON (C.I_CASE_ID) C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM nap."case" c
  INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  WHERE A.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND A.D_ACTION_PROCESSED BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>' AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  ORDER BY C.I_CASE_ID, A.I_ACTION_SEQ::INT4 DESC )
SELECT *
  FROM latest_actions
  ORDER BY actionProcessedDate, actionId ASC
  LIMIT 200 OFFSET <OFFSET>
),
new_cf AS MATERIALIZED (
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>),
latest_actions AS (SELECT DISTINCT ON (a.i_case_id) a.i_action_id, a.d_action_processed AS activity_date
  FROM in_scope_cases isc
  JOIN nap."action" a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  ORDER BY a.i_case_id, a.I_ACTION_SEQ::INT4 DESC),
page_keys AS MATERIALIZED (SELECT i_action_id
  FROM latest_actions
  ORDER BY activity_date, i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN nap."action" a ON a.i_action_id = pk.i_action_id
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedDate, actionId ASC
),
new_df AS MATERIALIZED (
WITH latest_actions AS (SELECT DISTINCT ON (a.i_case_id) a.i_action_id, a.d_action_processed AS activity_date
  FROM nap."action" a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM nap."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
  ORDER BY a.i_case_id, a.I_ACTION_SEQ::INT4 DESC),
page_keys AS MATERIALIZED (SELECT i_action_id
  FROM latest_actions
  ORDER BY activity_date, i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN nap."action" a ON a.i_action_id = pk.i_action_id
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedDate, actionId ASC
)
SELECT (SELECT count(*) FROM old_page) AS old_rows,
       (SELECT count(*) FROM new_cf)   AS new_rows_cases_first,
       (SELECT count(*) FROM new_df)   AS new_rows_date_first,
       (SELECT array_agg(t.actionid ORDER BY t.actionprocesseddate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT array_agg(t.actionid ORDER BY t.actionprocesseddate, t.actionid) FROM new_cf t) AS keys_match_cases_first,
       (SELECT array_agg(t.actionid ORDER BY t.actionprocesseddate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT array_agg(t.actionid ORDER BY t.actionprocesseddate, t.actionid) FROM new_df t) AS keys_match_date_first,
       (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocesseddate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocesseddate, t.actionid) FROM new_cf t) AS rows_match_cases_first,
       (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocesseddate, t.actionid) FROM old_page t) IS NOT DISTINCT FROM (SELECT string_agg(md5(t::text), ',' ORDER BY t.actionprocesseddate, t.actionid) FROM new_df t) AS rows_match_date_first;
```
### 1.4 Part 1 run list (per region)

| # | Block | Consumer | Range | `<OFFSET>` | Heading in `p1-<us|uk>-equiv.txt` |
|---|---|---|---|---|---|
| 1 | E1 | median | 2d | — | `### E1 median 2d` |
| 2 | E2 | median | 2d | 0 | `### E2 median 2d page1` |
| 3 | E1 | median | 60d | — | `### E1 median 60d` |
| 4 | E2 | median | 60d | 0 | `### E2 median 60d page1` |
| 5 | E1 | largest | 2d | — | `### E1 largest 2d` |
| 6 | E2 | largest | 2d | 0 | `### E2 largest 2d page1` |
| 7 | E1 | largest | 60d | — | `### E1 largest 60d` |
| 8 | E2 | largest | 60d | 0 | `### E2 largest 60d page1` |
| 9 | E2 | largest | 60d | deep (§1.2) | `### E2 largest 60d deep OFFSET=<n>` |
| 10 | E1-MAX | largest | 60d | — | `### E1-MAX largest 60d` |
| 11 | E2-MAX | largest | 60d | 0 | `### E2-MAX largest 60d page1` |

Expected result: every `*_match` column is `t`. **If any is `f`, stop and send me that file.** It holds counts and flags only.

---

## Part 2. New-path plans: EXPLAIN (ANALYZE, BUFFERS)

> Again: **only after P0 shows the Stage 2 indexes valid on this replica.**

### 2.0 Size check: which start point the app would choose
The app runs this check per request when neither ARN nor merchantId is sent. It decides which count and page SQL are measured below. It returns counts only.
- Save the four rows per region (median / largest × 2d / 60d) into `docs/results-phase1/p1-us-sizecheck.txt` and `p1-uk-sizecheck.txt`, with headings such as `### median 2d`.
- **Run time class:** light. The check reads at most `<CAP>` case-index entries.

#### SIZE-US (run on the US replica)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '2min';
SET LOCAL lock_timeout = '5s';

SELECT s.n AS in_scope_cases_capped, <CAP>::bigint AS cap,
       CASE WHEN s.n < <CAP> THEN 'CASES_FIRST' ELSE 'DATE_FIRST' END AS start_point
FROM (
SELECT count(*)
  FROM (SELECT 1
  FROM wdp."case" c
  WHERE C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  LIMIT <CAP>) capped
) AS s(n);
```
#### SIZE-UK (run on the UK replica)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '2min';
SET LOCAL lock_timeout = '5s';

SELECT s.n AS in_scope_cases_capped, <CAP>::bigint AS cap,
       CASE WHEN s.n < <CAP> THEN 'CASES_FIRST' ELSE 'DATE_FIRST' END AS start_point
FROM (
SELECT count(*)
  FROM (SELECT 1
  FROM nap."case" c
  WHERE C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  LIMIT <CAP>) capped
) AS s(n);
```
### 2.1 Plan templates

How to run each plan:
1. Fill the placeholders.
2. **Run each block twice and save only the second run**, as in B3. The first run warms the cache.
3. Run `ROLLBACK;` between runs.
4. **If a block times out** (10 min): save `timeout at 10min`. Then run the same block with `EXPLAIN (BUFFERS, SETTINGS)` and no `ANALYZE`, and save that plan under the same file name.

Pick the template from the 2.0 result: `start_point = CASES_FIRST` uses the CF templates, `DATE_FIRST` uses the DF templates.

#### NEW-US-COUNT-CF
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>)
SELECT count(*)
  FROM in_scope_cases isc
  JOIN wdp.action a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>';
```
#### NEW-US-COUNT-DF
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT count(*)
  FROM wdp.action a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM wdp."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>);
```
#### NEW-US-PAGE-CF
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>),
page_keys AS MATERIALIZED (SELECT a.i_action_id
  FROM in_scope_cases isc
  JOIN wdp.action a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  ORDER BY a.d_action_processed_est, a.i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN wdp.action a ON a.i_action_id = pk.i_action_id
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedESTDate, actionId ASC;
```
#### NEW-US-PAGE-DF
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH page_keys AS MATERIALIZED (SELECT a.i_action_id
  FROM wdp.action a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed_est BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM wdp."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
  ORDER BY a.d_action_processed_est, a.i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.I_CASE as caseNumber,C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_ENRICH_FAILURE as failedEnrichment, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.D_ACTION_PROCESSED_EST as actionProcessedESTDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp, C.C_INPUT_METHOD as inputMethod,A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_MERCHANT_REF_NUMBER as merchantRefNumber,C.c_acro_frm as fromAcro,C.c_acro_to as toAcro,C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.i_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, C.i_pan_first6_last4 as panFirst6Last4, C.C_CUST_FIELD1 as customerField1,C.T_CUST_DSCRTN_2 as customerField2,C.T_CUST_DSCRTN_3 as customerField3, C.D_TR_TIMESTAMP as transactionTimestamp, C.D_DEPARTURE as departureDate, C.C_INVOICE as invoiceNumber, A.i_action_id as actionId, A.c_reason_category as reasonCategory, A.z_insrt AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, C.c_sls_pers_id as salesPersonId, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN wdp.action a ON a.i_action_id = pk.i_action_id
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedESTDate, actionId ASC;
```
#### NEW-US-SIZE (plan of the size check itself; largest consumer, 60 days)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '2min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT count(*)
  FROM (SELECT 1
  FROM wdp."case" c
  WHERE C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  LIMIT <CAP>) capped;
```
#### NEW-UK-COUNT-CF
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>)
SELECT count(*)
  FROM in_scope_cases isc
  JOIN nap."action" a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>';
```
#### NEW-UK-COUNT-DF
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT count(*)
  FROM nap."action" a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM nap."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>);
```
#### NEW-UK-PAGE-CF
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH in_scope_cases AS MATERIALIZED (<IN_SCOPE_CASES_BRANCHES>),
page_keys AS MATERIALIZED (SELECT a.i_action_id
  FROM in_scope_cases isc
  JOIN nap."action" a ON a.i_case_id = isc.i_case_id
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  ORDER BY a.d_action_processed, a.i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN nap."action" a ON a.i_action_id = pk.i_action_id
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedDate, actionId ASC;
```
#### NEW-UK-PAGE-DF
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
WITH page_keys AS MATERIALIZED (SELECT a.i_action_id
  FROM nap."action" a
  WHERE a.c_action_sta IN ('OPEN', 'ERROR', 'CLOSED') AND a.d_action_processed BETWEEN DATE '<FROM_DATE>' AND DATE '<TO_DATE>'
  AND EXISTS (SELECT 1
  FROM nap."case" c
  WHERE c.i_case_id = a.i_case_id AND C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>)
  ORDER BY a.d_action_processed, a.i_action_id
  LIMIT 200 OFFSET <OFFSET>)
SELECT C.I_CASE as caseNumber, C.I_ACQ_REFNCE_NUM as acqRefnceNum, C.D_CASE_STRT as caseStartDate,C.D_CASE_END as caseEndDate, C.A_DISPUTE_ORIG as disputeOrig,C.I_DESK as desk,C.C_CASE_SPECIAL_HANDLING as caseSpecialHandling,C.C_NTWK_CASE_ID as ntwkCaseId, C.I_CASE_ACTION_MAX_SEQ as caseActionMaxSeq,C.C_CASE_STA as caseStatus,C.C_CASE_NTWK as caseNetwork,C.C_CASE_WRKFLW_TYPE as workflowType, C.I_SOURCE_ICA as sourceIca,C.I_DESTINATION_ICA as destinationIca,C.C_PRODUCT_TYPE as productType,C.C_PRODUCT_SUB_TYPE as productSubType, C.C_SIG_PIN as sigPin,C.I_ISSUER_BIN as issuerBin,C.C_CASE_FINAL_LIABILITY as caseFinalLiability,C.C_LEVEL1_ENTITY as level1Entity, C.C_LEVEL2_ENTITY as level2Entity,C.C_LEVEL3_ENTITY as level3Entity,C.C_LEVEL4_ENTITY as level4Entity,C.C_LEVEL5_ENTITY as level5Entity, C.C_LEVEL6_ENTITY as level6Entity,C.C_LEVEL7_ENTITY as level7Entity,C.C_LEVEL8_ENTITY as level8Entity,C.C_LEVEL9_ENTITY as level9Entity,C.C_LEVEL10_ENTITY as level10Entity, C.C_LEVEL11_ENTITY as level11Entity,C.C_MERCHANT_NAME as merchantName,C.C_STE as state,C.N_CITY as city,C.C_CNTRY as country, C.C_MCC as mcc,C.I_NTWK_TRAN_ID as networkTranId,C.C_MRCHNT_ORDER_ID as mrchntOrderId,C.C_TR_TYPE as trType,C.D_TR as tr,C.A_PURCHASE_AMT as purchaseAmt, C.C_PURCHASE_CURRENCY as purchaseCurrency,C.I_PURCHASE_AMT_EXPONENT as purchaseAmtExpo,C.I_WPY_SETTLE_TRANID as wpySettleTranid,C.C_CHECKOUT_ID as checkoutId, C.I_ACCT_CDH_LST as acctCdhLst,C.I_ACCT_CDH as acctCdh,C.A_CNVERTED_PURCHASE_AMT as cnvertedPurchaseAmt,C.C_CNVERTED_PURCHASE_AMT_CURRENCY as cnvertedPurchaseAmtCurrency,C.I_CNVERTED_PURCHASE_AMT_EXPONENT as cnvertedPurchaseAmtExpo, C.C_ENTRY_MODE as entryMode,C.C_AVS_RESP as avsResp,C.I_RGSTR as rgstr,C.C_TOKEN as token,A.c_migration_sta as migrationStatus,A.I_ACTION_SEQ as actionSeq,A.C_SOURCE_CASE_ID as sourceCaseId,A.A_DISPUTE_AMT as disputeAmount, A.C_DISPUTE_CURRENCY as disputeCurrency,A.I_DISPUTE_CURRENCY_EXPONENT as disputeAmtExponent,A.C_NTWK_CHBK_REFNCE as networkChbkReferance,A.D_ACTION_PROCESSED as actionProcessedDate, A.C_ISSUER_DOC_IND as issuerDocIndicator,A.C_MRCH_DOC_IND as merchantDocIndicator,A.D_RESPONSE_DUE as responseDueDate,A.D_EXPIRY_DUE as expiryDueDate, A.D_ACTION_REPORTED as actionReportedDate,A.C_ACTION_IN_OUT as actionInOut,A.C_PARTIAL as partialAmountIndicator,A.C_PRE_NOTE as preNoteIndicator,A.C_OWNR as owner, A.x_dspt_amt_sgn as dsptAmountSign,A.C_REJECT_REASON2 as secondRejectReason,A.D_REJECT as rejectDate,A.C_NTWRK_PHASE_ID as networkPhaseId, A.C_CASE_STAGE as caseStage,A.C_ACTION_TYPE as actionType,A.C_REASON as reasonCode,A.X_UPDT as updatedBy,A.Z_UPDT as updatedTimestamp,C.C_CONFRM_FRAUD as fraudIndicator, C.C_ENRICH_FAILURE as failedEnrichment,C.C_INPUT_METHOD as inputMethod,C.C_FNS_COUNTER as fnsIndicator,C.C_REFUND as refundHandlingIndicator,C.C_CONFRM_REFUND as refundInd, A.C_TIMEFRAME_EXPIRED_IND as timeframeExpiredInd,A.A_DISPUTE_WORKABLE_AMT as disputeWorkableAmount,A.C_DISPUTE_WORKABLE_CURRENCY as disputeWorkableCurrency, C.C_ACQ_PLATFORM as platform, A.C_REVRSL_IND as reversalIndicator, A.C_DUPLICATE_IND as duplicateIndicator, A.C_SPEC_HANDLING as specHandling, C.C_ASSIGNMENT_RSN as assingmentReason, C.C_CONFRM_FRAUD as confirmedFraud, C.D_FNS as fraudNotificationServiceDate,C.C_MERCHANT_REF_NUMBER as merchantRefNumber,A.C_EQL_RECON_AMT_IND as equalReconciliationAmtInd, C.C_CVV as cvv,A.c_incomng_partial as incomingPartialInd, A.c_network_doc_ind as networkDocInd,A.i_dispute_workable_currency_exponent as disputeWorkableAmountExponent, A.a_cnverted_dispute_amt as convertedDisputeAmount,A.c_cnverted_dispute_amt_currency as convertedDisputeAmountCurrency,A.a_cnverted_dispute_exponent as convertedDisputeAmountExponent, A.a_post_amt as disputeSettlementAmount,A.c_post_currency as disputeSettlementAmountCurrency,A.c_post_amt_exponent as disputeSettlementAmountExponent,c.d_cse_ready as caseReadyDate, A.C_ACTION_STA as actionStatus, C.I_TICKET_NUMBER as ticketNumber, C.C_AUTH_TR as authCode, A.i_action_id as actionId, C.D_TR_TIMESTAMP as transactionTimestamp, A.c_reason_category as reasonCategory, C.i_pan_first6_last4 as panFirst6Last4, A.z_insrt as actionTimestamp, C.c_au_pan as accountUpdaterPAN, C.c_au_hash_pan as accountUpdaterHashPAN, A.x_insrt as createdBy, A.x_updt as lastUpdatedBy, A.x_insrt_display as insertedDisplayId, A.x_updt_display as updatedDisplayId, C.C_CUST_FIELD1 as customerField1, C.T_CUST_DSCRTN_2 as customerField2, /* C.T_CUST_DSCRTN_3 as customerField3, -- absent in UK prod (report 7) */ A.C_FUNCTION_CODE as functionCode, CASE WHEN cl.i_case IS NOT NULL THEN 'Y' ELSE NULL END AS caseLock, cl.locked_by AS lockedBy , case when A.C_ACTION_STA != 'CLOSED' THEN 1 when A.C_ACTION_STA = 'CLOSED' THEN 2 end AS sort_order_1, case when A.C_ACTION_STA = 'CLOSED' then -1 when A.D_EXPIRY_DUE is null or A.D_EXPIRY_DUE <= CURRENT_DATE THEN 0 else A.D_EXPIRY_DUE - CURRENT_DATE end AS sort_order_2
  FROM page_keys pk
  JOIN nap."action" a ON a.i_action_id = pk.i_action_id
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case
  ORDER BY actionProcessedDate, actionId ASC;
```
#### NEW-UK-SIZE (plan of the size check itself; largest consumer, 60 days)
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '2min';
SET LOCAL lock_timeout = '5s';

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT count(*)
  FROM (SELECT 1
  FROM nap."case" c
  WHERE C.C_ACQ_PLATFORM IN (<PLATFORM_LIST>) AND C.c_case_sta IN ('OPEN','CLOSED') AND <ENTITY_PREDICATE>
  LIMIT <CAP>) capped;
```
### 2.2 Part 2 run list (per region; replace `us` with `uk` for UK)

| # | File (`docs/results-phase1/…`) | Consumer | Range | Template | `<OFFSET>` | Run time class |
|---|---|---|---|---|---|---|
| 1 | `p1-us-median-2d-count.txt` | median | 2d | COUNT-CF or -DF per 2.0 | — | light |
| 2 | `p1-us-median-2d-page.txt` | median | 2d | PAGE-CF or -DF per 2.0 | 0 | light |
| 3 | `p1-us-median-60d-count.txt` | median | 60d | per 2.0 | — | light |
| 4 | `p1-us-median-60d-page.txt` | median | 60d | per 2.0 | 0 | light |
| 5 | `p1-us-largest-2d-count.txt` | largest | 2d | per 2.0 | — | light–medium |
| 6 | `p1-us-largest-2d-page.txt` | largest | 2d | per 2.0 | 0 | light–medium |
| 7 | `p1-us-largest-60d-count.txt` | largest | 60d | per 2.0 | — | medium |
| 8 | `p1-us-largest-60d-page.txt` | largest | 60d | per 2.0 | 0 | medium |
| 9 | `p1-us-largest-60d-page-deep.txt` | largest | 60d | per 2.0 | deep OFFSET from §1.2 | medium |
| 10 | `p1-us-largest-60d-sizecheck-plan.txt` | largest | 60d | NEW-US-SIZE with `cap_60d` | — | light |

In the header line of each file, add the start point used (from 2.0) next to the date, the replica and `n_cases`.

Optional, if you want to separate the effect of the indexes from the effect of the new path:
- After the indexes exist, run the **old** SQL plans again on the replica, with FINALIZE B3.2 templates US-COUNT, US-PAGE, UK-COUNT and UK-PAGE and the same placeholders.
- Save them as `p1-us-old-<consumer>-<range>-<count|page>.txt`.
- Without them, the "before" side of the report is B3, which was **run on the writer, without the indexes**.

**Caveats (same as B3):**
- Literal values reproduce the planner's custom plans. The app binds parameters, and the JDBC driver can switch a frequently run statement to a cached generic plan (`plan_cache_mode = auto`). That is not measured here.
- `EXPLAIN ANALYZE` executes the query on the replica. Hence the 10-minute timeouts.
- Warm cache only (second run). Cold cache is not measured.

### 2.3 Optional: index usage on the replica
To see which indexes the replica uses once the new path is live: take the Stage 2 V4 snapshot (`GDP-ACTIVITIES-PHASE1-INDEXES.md`, "Verifying each index") before and after a period of traffic. Save both to `docs/results-phase1/p1-<us|uk>-index-usage.txt`. These are counters only.

---

## 3. Results checklist (`docs/results-phase1/`)

| ☐ | File | From | DB |
|---|---|---|---|
| ☐ | `p1-us-prereq.txt` | P0-US | US replica |
| ☐ | `p1-uk-prereq.txt` | P0-UK | UK replica |
| ☐ | `p1-us-equiv.txt` | Part 1, runs 1–11 | US replica |
| ☐ | `p1-uk-equiv.txt` | Part 1, runs 1–11 | UK replica |
| ☐ | `p1-us-sizecheck.txt` | 2.0, 4 rows | US replica |
| ☐ | `p1-uk-sizecheck.txt` | 2.0, 4 rows | UK replica |
| ☐ | `p1-us-median-2d-count.txt` | 2.2 #1 | US replica |
| ☐ | `p1-us-median-2d-page.txt` | 2.2 #2 | US replica |
| ☐ | `p1-us-median-60d-count.txt` | 2.2 #3 | US replica |
| ☐ | `p1-us-median-60d-page.txt` | 2.2 #4 | US replica |
| ☐ | `p1-us-largest-2d-count.txt` | 2.2 #5 | US replica |
| ☐ | `p1-us-largest-2d-page.txt` | 2.2 #6 | US replica |
| ☐ | `p1-us-largest-60d-count.txt` | 2.2 #7 | US replica |
| ☐ | `p1-us-largest-60d-page.txt` | 2.2 #8 | US replica |
| ☐ | `p1-us-largest-60d-page-deep.txt` | 2.2 #9 | US replica |
| ☐ | `p1-us-largest-60d-sizecheck-plan.txt` | 2.2 #10 | US replica |
| ☐ | `p1-uk-median-2d-count.txt` | 2.2 #1 | UK replica |
| ☐ | `p1-uk-median-2d-page.txt` | 2.2 #2 | UK replica |
| ☐ | `p1-uk-median-60d-count.txt` | 2.2 #3 | UK replica |
| ☐ | `p1-uk-median-60d-page.txt` | 2.2 #4 | UK replica |
| ☐ | `p1-uk-largest-2d-count.txt` | 2.2 #5 | UK replica |
| ☐ | `p1-uk-largest-2d-page.txt` | 2.2 #6 | UK replica |
| ☐ | `p1-uk-largest-60d-count.txt` | 2.2 #7 | UK replica |
| ☐ | `p1-uk-largest-60d-page.txt` | 2.2 #8 | UK replica |
| ☐ | `p1-uk-largest-60d-page-deep.txt` | 2.2 #9 | UK replica |
| ☐ | `p1-uk-largest-60d-sizecheck-plan.txt` | 2.2 #10 | UK replica |
| ☐ | (optional) `p1-<us|uk>-old-*.txt` | 2.2 optional | replica |
| ☐ | (optional) `p1-<us|uk>-index-usage.txt` | 2.3 | replica |

**Not saved, by design:** P1-US output, P1-UK step 1 cell and step 2 output.

**Suggested order:**
1. P0.
2. P1.
3. Part 1, median before largest and 2d before 60d. Stop at the first `f`.
4. 2.0.
5. Part 2, smallest first.

When you reply **"results ready"**, I will write `docs/GDP-ACTIVITIES-PHASE1-VERIFY-REPORT.md`. It will contain, facts only:
- before / after per plan (time, rows read, driving access path), with B3 as "before" unless the optional old-path runs exist;
- the equivalence outcome;
- the start points chosen;
- anything unexpected.

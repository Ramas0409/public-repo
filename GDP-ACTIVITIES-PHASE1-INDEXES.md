# GDP Activities Phase 1 — Index scripts (for the DBA, not executed)

*2026-11-11. Scripts for decision D8, from `GDP-ACTIVITIES-FINALIZE-FINDINGS.md`. **Nothing here has been run against US or UK.***

Inputs:
- column names and types: `docs/results/b1-us-indexes.txt` (B1.6, lines 114-327) and `docs/results/b1-uk-indexes.txt` (B1.6, lines 109-337);
- statistics: B1.7;
- existing indexes: report §2;
- visibility: report §6.

**Syntax check only.** All 7 `CREATE INDEX CONCURRENTLY` statements, two of the `DROP INDEX CONCURRENTLY` rollbacks and the `pg_index` validity query were run on the **local** PostgreSQL 17.10. That ran in throwaway schemas holding empty copies of the column definitions (`LIKE wdp.case`, `LIKE nap.action` and so on). The schemas were dropped afterwards.
- The local `wdp.action` has no `d_action_processed_est`, so it was added to the throwaway copy as `date` (prod type, B1.6 line 175).
- Production is 17.5. The check proves syntax and option support only, not size, build time or plans.

## Column facts used (B1.6)

| Column | Table | Type | Nullable | null_frac / n_distinct (B1.7) US · UK |
|---|---|---|---|---|
| `c_level4_entity` | case | varchar | yes | 0.0003 / 7,752 · 0.00003 / 2,842 |
| `c_level5_entity` | case | varchar | yes | **0.257** / 869 · 0.089 / 815 |
| `c_acq_platform` | case | varchar | yes | 0 / 2 · 0 / **1** |
| `c_case_sta` | case | varchar | yes | 0 / 2 · 0 / 2 |
| `i_case_id` | case, action | bigint | no | — |
| `i_action_id` | action | bigint | no | — |
| `c_action_sta` | action | varchar | yes | 0 / 2 · 0 / 2 |
| `d_action_processed_est` | action (US only) | date | yes | **0.435** / 785 · column absent in UK |
| `d_action_processed` | action | date | yes | 0 / 1,453 · 0 / 878 |

Entity labels: US Chain = `c_level4_entity`, SuperChain = `c_level5_entity`. UK Company = `c_level4_entity`, Group = `c_level5_entity`.

## Key or INCLUDE: the rule used here

Each "carried" filter column (`c_acq_platform`, `c_case_sta`, `c_action_sta`) is a **key column**, placed after the columns that define the scan range. Only pure output columns (`i_case_id` on `case`, `i_action_id` / `i_case_id` on `action`) are **INCLUDE**. Why:

1. **Filters on key columns are checked inside the index, before any heap visit.** Filters on INCLUDE columns are not.
   - B-tree checks a condition on any key column against the index tuple, even one that does not narrow the range. The local check showed `c_action_sta = ANY(...)` inside the `Index Cond` of both action indexes.
   - A condition on an INCLUDE column is a plain `Filter`. In an index-only scan, PostgreSQL checks visibility first and fetches the heap for every tuple on a page not marked all-visible, before the filter runs.
   - Report §6: UK `action` and `case` are only 62-64 % all-visible, and recent US action rows still needed 51 % heap fetches. So a filter on an INCLUDE column saves little exactly where activities reads, in the recent window.
2. **Bitmap index scans cannot use INCLUDE columns.** Today's activities SQL ORs the entity columns together, for example `(C.C_LEVEL5_ENTITY IN (...) OR C.C_LEVEL4_ENTITY IN (...))` (`USDisputeCaseSearchDAOImpl.java:787-831`; UK equivalent). The planner serves that with a BitmapOr of the two entity indexes. With platform and status as key columns, they still filter inside each bitmap index scan; as INCLUDE columns they would be rechecked on the heap only.
3. **Low cardinality makes key columns cheap.** Platform and status have 1-2 distinct values. They add almost nothing to internal pages and do not change the index's usefulness for entity-only lookups.
4. **Sort order.** On UK #7, `i_action_id` must be a key column so that the index yields today's sort order (activity date, then action id) and a page can stop early. On #3 and #6, ordering inside a case is not useful (rows from many cases are re-sorted), so `i_action_id` is INCLUDE.

Stage 3 (new query path) probes each entity column in its own branch (UNION of per-column lookups) instead of one OR. That way each branch can be a plain index or index-only scan on its own index. With today's OR, the indexes are still used, through BitmapOr plus a heap recheck.

## Common build procedure (each index, each region)

- Run on the **writer** of the region. The index replicates to the read replicas through WAL.
- `CREATE INDEX CONCURRENTLY` cannot run inside a transaction block, so each statement goes in its own autocommit call. It takes a SHARE UPDATE EXCLUSIVE lock, so inserts and updates continue.
- It waits for transactions that are open on the writer when it starts. Long-running transactions delay the build.
- Settings for the DBA to choose (not prescribed here):
  - `maintenance_work_mem` (production default not measured);
  - `max_parallel_maintenance_workers` (parallel B-tree builds work with CONCURRENTLY);
  - a quiet window.
- Expect extra WAL roughly equal to the index size, and replica apply lag during the build.
- **If a build fails or is cancelled**, PostgreSQL leaves an INVALID index behind. Find it with V2 below, drop it with the rollback statement, then rerun.
- Order (findings, rollout): build and verify all indexes, re-measure plans (Stage 4), then switch the code path.

```sql
-- session settings for each build session (adjust as DBA policy requires)
SET statement_timeout = 0;
SET lock_timeout = '5s';        -- fail fast if the SHARE UPDATE EXCLUSIVE lock is blocked; retry later
-- SET maintenance_work_mem = '...';   -- DBA to size
```

Size estimate method:
- (rows × index tuple bytes) ÷ (8,192 × 0.90 leaf fill);
- tuple bytes = 8-byte header + aligned data + 4-byte line pointer;
- B1.7 `avg_width` and the B1.1 live row counts.

This is the size of a **fresh build**. Comparable production indexes are 1.5-2× their fresh size after growth (for example US `ix_case_level2_entity`, 1,613 MB). Deduplication does not apply, because every tuple carries a unique id and INCLUDE indexes do not deduplicate.

---

## US (`wdp`)

### US-1 · case by Chain

- **Purpose:** find a caller's cases by Chain without the full `case` scan seen in US 60-day counts (report §5, #3 and #7: 15.7 M rows per call). It returns `i_case_id` for the join to `action`.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level4_entity_scope
    ON wdp."case" (c_level4_entity, c_acq_platform, c_case_sta)
    INCLUDE (i_case_id);
```
- **Key / INCLUDE:**
  - key `c_level4_entity` (range), then `c_acq_platform` and `c_case_sta` (filters checked in the index);
  - INCLUDE `i_case_id` (output only).
- **Size class:** medium. About 44 B per tuple × 15.7 M rows ≈ **0.75 GB fresh**; expect 1-1.5 GB over time.
- **Rollback:** `DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_case_level4_entity_scope;`

### US-2 · case by SuperChain

- **Purpose:** same as US-1, for SuperChain scope (the largest US caller uses both, B3.1).

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level5_entity_scope
    ON wdp."case" (c_level5_entity, c_acq_platform, c_case_sta)
    INCLUDE (i_case_id);
```
- **Key / INCLUDE:** as US-1.
- **Size class:** medium, ≈ **0.75 GB fresh**.
- **Option, not the default:** 25.7 % of rows have NULL SuperChain. Adding `WHERE c_level5_entity IS NOT NULL` would cut about a quarter of the size. The planner can still use it, because `= ANY(...)` / `IN (...)` on the column implies NOT NULL. No production index is partial today (report §2); the DBA decides.
- **Rollback:** `DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_case_level5_entity_scope;`

### US-3 · action by case id, then activity date

- **Purpose:** in a cases-first start, fetch each case's actions in the date range directly. Today that goes through `ix_action_case_id (i_case_id)` and checks the date and status on the heap row (report §3 #6).

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_case_processed_est_sta
    ON wdp.action (i_case_id, d_action_processed_est, c_action_sta)
    INCLUDE (i_action_id);
```
- **Key / INCLUDE:**
  - key `i_case_id` (equality), then `d_action_processed_est` (range);
  - `c_action_sta` as key (filter checked in the index);
  - INCLUDE `i_action_id` (output only).
- **Size class:** large. About 44 B × 58.4 M rows ≈ **2.7 GB fresh**; expect 3-4 GB over time. US `action` indexes are already 48 GB (report §2.1), and this adds write cost to every action insert.
- **Option, not the default:** 43.5 % of US rows have `d_action_processed_est` NULL (B1.7; report §1.2: every row before 2024-09). Activities can never return them. Adding `WHERE d_action_processed_est IS NOT NULL` would cut the index by about 43 % (≈ 1.5 GB fresh), and `BETWEEN` on the column implies NOT NULL. The trade-off is that a request without a date range could not use it (it falls back to `ix_action_case_id`); COMP-21 always sends a range (`DisputesValidator.validateActivityDateRange`). DBA and architect to decide.
- Existing `ix_action_processed_est_actionid_case` and `ix_case_mid` stay as they are (D4).
- **Rollback:** `DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_action_case_processed_est_sta;`

## UK (`nap`)

### UK-4 · case by Company

- **Purpose:** remove the full `nap.case` scan that every UK activities call pays today (report §5, #9-16: 5.5 M rows per call).

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level4_entity_scope
    ON nap."case" (c_level4_entity, c_acq_platform, c_case_sta)
    INCLUDE (i_case_id);
```
- **Key / INCLUDE:** as US-1. UK `c_acq_platform` has a single value (B1.7), so as a key column it costs about 5 bytes per tuple and filters nothing. It is kept for the same definition as US, and so that a request carrying a non-matching platform is still answered from the index.
- **Size class:** small. About 52 B × 5.5 M rows ≈ **0.31 GB fresh**; expect 0.4-0.5 GB.
- **Rollback:** `DROP INDEX CONCURRENTLY IF EXISTS nap.ix_case_level4_entity_scope;`

### UK-5 · case by Group

- **Purpose:** as UK-4, for Group scope (both B3.1 UK callers are Group-scoped).

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level5_entity_scope
    ON nap."case" (c_level5_entity, c_acq_platform, c_case_sta)
    INCLUDE (i_case_id);
```
- **Key / INCLUDE:** as US-1.
- **Size class:** small, ≈ **0.31 GB fresh**. The same partial-index option as US-2 applies (8.9 % NULL; small saving).
- **Rollback:** `DROP INDEX CONCURRENTLY IF EXISTS nap.ix_case_level5_entity_scope;`

### UK-6 · action by case id, then activity date

- **Purpose:** cases-first start. Today UK fetches every action of every in-scope case and filters the date afterwards, which is why UK cost does not change with the range (report §5).

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_case_processed_sta
    ON nap.action (i_case_id, d_action_processed, c_action_sta)
    INCLUDE (i_action_id);
```
- **Key / INCLUDE:** as US-3.
- **Size class:** medium. About 44 B × 19.4 M rows ≈ **0.9 GB fresh**; expect 1-1.5 GB.
- **Rollback:** `DROP INDEX CONCURRENTLY IF EXISTS nap.ix_action_case_processed_sta;`

### UK-7 · action by activity date, then action id

- **Purpose:** date-first start, plus page order. Today UK has no index on `d_action_processed` at all (report §3 #7).
  - For callers with many cases, it reads the range's actions in today's sort order (date ascending, action id ascending), and a page can stop after `offset + page size` matches.
  - The same index serves the date-first count.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_processed_actionid_sta
    ON nap.action (d_action_processed, i_action_id, c_action_sta)
    INCLUDE (i_case_id);
```
- **Key / INCLUDE:**
  - key `d_action_processed` (range), then `i_action_id` (gives the sort order);
  - `c_action_sta` as key (filter checked in the index);
  - INCLUDE `i_case_id` (output, used for the join to the caller's cases).
- Same leading columns as US `ix_action_processed_est_actionid_case`, which lacks the status column. The US one stays as is, per the brief.
- **Size class:** medium, ≈ **0.9 GB fresh**.
- **Rollback:** `DROP INDEX CONCURRENTLY IF EXISTS nap.ix_action_processed_actionid_sta;`

---

## LOAD ONLY — align the load environment's indexes with prod (never run in prod)

Source: load B1.2 (`docs/results-phase1/load/l1-load-inventory.txt`), compared with prod B1.2 (`docs/results/b1-*.txt`, report §2). Load keeps `wdp` and `nap` in **one database**, so every statement below runs on the same connection.

**Order matters for a fair before/after in load:**
1. Run **A1–A3** (indexes prod has but load lacks).
2. Run A4 (`ANALYZE`).
3. Take the load baseline: FINALIZE B3.1 + B3.2, step L2 in VERIFY-SQL §0.1.
4. **Only then** build the Phase 1 indexes US-1…UK-7 above.

If A1–A3 and US-1…UK-7 are built together, load has no "before" with prod's index set, and the only "before" left is prod B3.

### A0. Pre-checks for the unique indexes (read-only)
A unique build fails if load holds duplicates; load was copied without these constraints. Each query should return **0 rows**. **Run time class:** medium (GROUP BY over 26.7 M / 13.2 M rows).
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15min';
SET LOCAL lock_timeout = '5s';
SELECT 'wdp.action i_action_id' AS chk, count(*) AS duplicate_keys FROM (
  SELECT i_action_id FROM wdp.action GROUP BY 1 HAVING count(*) > 1) d
UNION ALL
SELECT 'wdp.action (i_case, i_action_seq)', count(*) FROM (
  SELECT i_case, i_action_seq FROM wdp.action GROUP BY 1, 2 HAVING count(*) > 1) d
UNION ALL
SELECT 'nap.action (i_case, i_action_seq)', count(*) FROM (
  SELECT i_case, i_action_seq FROM nap.action GROUP BY 1, 2 HAVING count(*) > 1) d
UNION ALL
SELECT 'wdp.case_lock i_case', count(*) FROM (
  SELECT i_case FROM wdp.case_lock GROUP BY 1 HAVING count(*) > 1) d
UNION ALL
SELECT 'nap.case_lock i_case', count(*) FROM (
  SELECT i_case FROM nap.case_lock GROUP BY 1 HAVING count(*) > 1) d;
```
Then run `ROLLBACK;`. Interpreting the result:
- Every `duplicate_keys` should be **0**.
- If a check shows **> 0**, do not build that unique index. Build it as a plain index (same columns, without `UNIQUE`) and note it. The planner then lacks the uniqueness guarantee, which matters for `case_lock` (the lock join).

### A0 result in load (2026-11-12) and A0b (option 1, chosen)

| Check | Duplicate keys | Action |
|---|---|---|
| `wdp.action i_action_id` | 0 | build A1.1 as written |
| `wdp.action (i_case, i_action_seq)` | **1** | build **A1.2 without `UNIQUE`** (statement below); activities does not use it |
| `wdp.case_lock i_case` | **20** | **A0b**: remove the extra lock rows, keeping one per case, then build A1.6 as unique |
| `nap.action (i_case, i_action_seq)` | 0 | A2.1 as written |
| `nap.case_lock i_case` | 0 | A2.2 as written |

A1.2 for load, replacing the unique statement in A1:
```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS uq_action_case_action_seq
    ON wdp.action (i_case, i_action_seq);   -- load: not unique (1 duplicate key, A0); prod has it as UNIQUE
```

**If the `UNIQUE` version from A1 was already run in load** (it fails with SQL state 23505, "could not create unique index … is duplicated"):
- A failed `CREATE INDEX CONCURRENTLY` leaves an **INVALID** index with that name behind.
- `IF NOT EXISTS` would then skip the non-unique build with only a notice, and leave the broken index in place.

Clean up first, then build, then check (load, 2026-11-12):
```sql
-- 1. drop the invalid leftover of the failed unique build
DROP INDEX CONCURRENTLY IF EXISTS wdp.uq_action_case_action_seq;

-- 2. build it without UNIQUE
CREATE INDEX CONCURRENTLY IF NOT EXISTS uq_action_case_action_seq
    ON wdp.action (i_case, i_action_seq);
```
```sql
-- 3. check every A1 index: expect 7 rows, indisvalid = t, indisready = t;
--    uq_action_case_action_seq with is_unique = f, pin_action_pkey and case_lock_unique with is_unique = t
SELECT c.relname, i.indisvalid, i.indisready, i.indisunique AS is_unique
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'wdp'
  AND c.relname IN ('pin_action_pkey','uq_action_case_action_seq','ix_action_processed_actionid_case',
                    'ix_action_status_owner_processed_actionid_case','ix_case_liability',
                    'case_lock_unique','idx_case_lock_i_case')
ORDER BY 1;
```
The same applies to any other `CREATE INDEX CONCURRENTLY` in this file that fails: run its `DROP INDEX CONCURRENTLY IF EXISTS …` (rollback statement) before retrying. V3 ("Verifying each index") lists every INVALID index in both schemas.

#### A0b. Remove duplicate `wdp.case_lock` rows (load only; data change, backed up)
**Why:** the old activities SQL LEFT JOINs `case_lock` on `i_case`, in its count too. A case with 2+ lock rows is then counted, and paged, once per lock row. Prod's unique constraint rules that out. Without this step, load equivalence (VERIFY Part 1) could show a mismatch that cannot happen in prod.

**Which row is kept:** per `i_case`, the most recent lock (`locked_at DESC`, then highest `id`). The removed rows are first copied to `wdp.p1_bak_case_lock_extra`, and deleted by primary key (`id`, `case_lock_pk`). In prod nothing references `case_lock`: no foreign key into it and no trigger on it (B1.3, B1.4).

Steps:
1. Run FINALIZE **B1.2d** first and note `extra_lock_rows`.
2. Run this as one script. It is **not** read-only, and it ends without COMMIT:
```sql
BEGIN;
SET LOCAL statement_timeout = '5min';
SET LOCAL lock_timeout = '5s';

CREATE TABLE wdp.p1_bak_case_lock_extra AS
SELECT cl.*
FROM wdp.case_lock cl
JOIN (SELECT id,
             row_number() OVER (PARTITION BY i_case ORDER BY locked_at DESC, id DESC) AS rn
      FROM wdp.case_lock) r ON r.id = cl.id
WHERE r.rn > 1;

DELETE FROM wdp.case_lock cl
USING wdp.p1_bak_case_lock_extra b
WHERE cl.id = b.id;

SELECT (SELECT count(*) FROM wdp.p1_bak_case_lock_extra)                                 AS rows_backed_up_and_deleted,
       (SELECT count(*) FROM (SELECT i_case FROM wdp.case_lock GROUP BY 1 HAVING count(*) > 1) d) AS duplicates_left;
```
3. Check the last result:
   - If `rows_backed_up_and_deleted` = B1.2d `extra_lock_rows` **and** `duplicates_left` = 0, run `COMMIT;`.
   - Otherwise run `ROLLBACK;`, which undoes both the backup table and the delete, and send me the result.
4. Build A1.6 (`case_lock_unique`) and A1.7.

**Undo A0b** (only after dropping `case_lock_unique`, otherwise the insert fails):
```sql
DROP INDEX CONCURRENTLY IF EXISTS wdp.case_lock_unique;
INSERT INTO wdp.case_lock SELECT * FROM wdp.p1_bak_case_lock_extra;
DROP TABLE wdp.p1_bak_case_lock_extra;
```
Once load testing is finished and the undo is no longer needed, drop the backup table: `DROP TABLE wdp.p1_bak_case_lock_extra;`.

### A1. US (`wdp`): indexes in prod, missing in load

Prod names and key columns as in report §2.1–§2.3.
- In prod, `pin_action_pkey`, `uq_action_case_action_seq` and `case_lock_unique` are **constraints** (primary key / unique) backed by these indexes. A unique index alone gives the planner the same access path and uniqueness.
- Attaching them as constraints (`ALTER TABLE … ADD CONSTRAINT … USING INDEX`) is optional, and not needed for these tests.

| # | Prod index | Why it matters for activities |
|---|---|---|
| A1.1 | `pin_action_pkey (i_action_id)`, PK | **Required.** The new page fetches its rows by `i_action_id`; without it, load scans `action` |
| A1.2 | `uq_action_case_action_seq (i_case, i_action_seq)`, unique | not used by the activities SQL; kept for parity |
| A1.3 | `ix_action_processed_actionid_case (d_action_processed, i_action_id, i_case_id)` | not used by US activities (US filters on EST); parity, and UI date searches |
| A1.4 | `ix_action_status_owner_processed_actionid_case (c_action_sta, c_ownr, d_action_processed, i_action_id, i_case_id)` | parity |
| A1.5 | `ix_case_liability (c_case_final_liability)` | parity |
| A1.6 | `case_lock_unique (i_case)`, unique | **Required.** The lock join on `i_case`; load US `case_lock` has 66,481 rows and no index on `i_case` |
| A1.7 | `idx_case_lock_i_case (i_case)` | duplicate of A1.6 in prod (candidate to retire); kept for parity |

```sql
-- session settings, as in "Common build procedure"
SET statement_timeout = 0;
SET lock_timeout = '5s';

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS pin_action_pkey
    ON wdp.action (i_action_id);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_action_case_action_seq
    ON wdp.action (i_case, i_action_seq);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_processed_actionid_case
    ON wdp.action (d_action_processed, i_action_id, i_case_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_status_owner_processed_actionid_case
    ON wdp.action (c_action_sta, c_ownr, d_action_processed, i_action_id, i_case_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_liability
    ON wdp."case" (c_case_final_liability);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS case_lock_unique
    ON wdp.case_lock (i_case);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_case_lock_i_case
    ON wdp.case_lock (i_case);
```
Each statement runs on its own (CONCURRENTLY cannot run inside a transaction block). If a build fails, run V3 and the rollback statement, then retry.

**Rollback (A1):**
```sql
DROP INDEX CONCURRENTLY IF EXISTS wdp.pin_action_pkey;
DROP INDEX CONCURRENTLY IF EXISTS wdp.uq_action_case_action_seq;
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_action_processed_actionid_case;
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_action_status_owner_processed_actionid_case;
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_case_liability;
DROP INDEX CONCURRENTLY IF EXISTS wdp.case_lock_unique;
DROP INDEX CONCURRENTLY IF EXISTS wdp.idx_case_lock_i_case;
```

### A2. UK (`nap`): indexes in prod, missing in load

| # | Prod index | Why |
|---|---|---|
| A2.1 | `uq_action_case_action_seq (i_case, i_action_seq)`, unique | parity (not used by activities) |
| A2.2 | `case_lock_unique (i_case)`, unique | the lock join; UK load `case_lock` has 3 rows, so the effect is small |
| A2.3 | `idx_case_lock_i_case (i_case)` | duplicate of A2.2 in prod; parity |

```sql
SET statement_timeout = 0;
SET lock_timeout = '5s';

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_action_case_action_seq
    ON nap.action (i_case, i_action_seq);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS case_lock_unique
    ON nap.case_lock (i_case);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_case_lock_i_case
    ON nap.case_lock (i_case);
```
**Rollback (A2):**
```sql
DROP INDEX CONCURRENTLY IF EXISTS nap.uq_action_case_action_seq;
DROP INDEX CONCURRENTLY IF EXISTS nap.case_lock_unique;
DROP INDEX CONCURRENTLY IF EXISTS nap.idx_case_lock_i_case;
```

### A3. UK (`nap`): indexes in load that prod does not have — optional set-aside

Load UK has 12 indexes that prod UK lacks: 8 on `action`, 4 on `case`. Several already cover what UK-4…UK-7 add, so a UK "before" in load will **not** show prod's full scans of `case`.
- To reproduce prod's UK baseline, drop them before L2.
- Recreate them afterwards if other load testing needs them. The definitions below are copied exactly from load B1.2.
- **Check with whoever owns load testing before dropping.** The usage counts are non-zero for most (load B1.2 `idx_scan`).

| Load-only index | Definition (load B1.2) | Size |
|---|---|---|
| `nap.action_c_action_sta_action_type_idx` | `(c_action_sta, c_ownr, c_action_type, d_action_processed)` | 67 MB |
| `nap.action_c_action_sta_idx` | `(c_action_sta, c_ownr, d_action_processed)` | 66 MB |
| `nap.action_c_case_stage_idx` | `(c_case_stage, c_action_type, d_action_processed)` | 66 MB |
| `nap.action_d_action_processed_idx` | `(d_action_processed)` | 64 MB |
| `nap.action_d_action_processed_idx_action_sta_case_id` | `(d_action_processed, c_action_sta, i_case_id)` | 334 MB |
| `nap.idx_nap_action_processed_status` | `(d_action_processed, c_action_sta, i_case_id, i_action_id)` | 456 MB |
| `nap.idx_nap_action_status_processed` | `(c_action_sta, d_action_processed, i_case_id, i_action_id)` | 456 MB |
| `nap.ix_action_case_reported` | `(i_case_id, d_action_reported) INCLUDE (a_dispute_amt, i_action_seq, c_case_stage, c_action_type, c_pre_note, c_action_sta)` | 617 MB |
| `nap.case_c_level4_entity_idx` | `(c_level4_entity)` | 46 MB |
| `nap.case_c_level5_entity_idx` | `(c_level5_entity)` | 45 MB |
| `nap.idx_nap_case_platform_status_entity` | `(c_acq_platform, c_case_sta, c_level4_entity, c_level5_entity)` | 22 MB |
| `nap.ix_case_entity_platform` | `(c_level4_entity, c_acq_platform, i_case_id) INCLUDE (i_case_action_max_seq, c_case_sta, c_tr_type, c_case_final_liability)` | 215 MB |


**Set aside (drop) — only if agreed:**
```sql
DROP INDEX CONCURRENTLY IF EXISTS nap.action_c_action_sta_action_type_idx;
DROP INDEX CONCURRENTLY IF EXISTS nap.action_c_action_sta_idx;
DROP INDEX CONCURRENTLY IF EXISTS nap.action_c_case_stage_idx;
DROP INDEX CONCURRENTLY IF EXISTS nap.action_d_action_processed_idx;
DROP INDEX CONCURRENTLY IF EXISTS nap.action_d_action_processed_idx_action_sta_case_id;
DROP INDEX CONCURRENTLY IF EXISTS nap.idx_nap_action_processed_status;
DROP INDEX CONCURRENTLY IF EXISTS nap.idx_nap_action_status_processed;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_action_case_reported;
DROP INDEX CONCURRENTLY IF EXISTS nap.case_c_level4_entity_idx;
DROP INDEX CONCURRENTLY IF EXISTS nap.case_c_level5_entity_idx;
DROP INDEX CONCURRENTLY IF EXISTS nap.idx_nap_case_platform_status_entity;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_case_entity_platform;
```
**Recreate (rollback of A3), exact load definitions:**
```sql
SET statement_timeout = 0;
SET lock_timeout = '5s';
CREATE INDEX CONCURRENTLY IF NOT EXISTS action_c_action_sta_action_type_idx ON nap.action USING btree (c_action_sta, c_ownr, c_action_type, d_action_processed);
CREATE INDEX CONCURRENTLY IF NOT EXISTS action_c_action_sta_idx ON nap.action USING btree (c_action_sta, c_ownr, d_action_processed);
CREATE INDEX CONCURRENTLY IF NOT EXISTS action_c_case_stage_idx ON nap.action USING btree (c_case_stage, c_action_type, d_action_processed);
CREATE INDEX CONCURRENTLY IF NOT EXISTS action_d_action_processed_idx ON nap.action USING btree (d_action_processed);
CREATE INDEX CONCURRENTLY IF NOT EXISTS action_d_action_processed_idx_action_sta_case_id ON nap.action USING btree (d_action_processed, c_action_sta, i_case_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_nap_action_processed_status ON nap.action USING btree (d_action_processed, c_action_sta, i_case_id, i_action_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_nap_action_status_processed ON nap.action USING btree (c_action_sta, d_action_processed, i_case_id, i_action_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_case_reported ON nap.action USING btree (i_case_id, d_action_reported) INCLUDE (a_dispute_amt, i_action_seq, c_case_stage, c_action_type, c_pre_note, c_action_sta);
CREATE INDEX CONCURRENTLY IF NOT EXISTS case_c_level4_entity_idx ON nap."case" USING btree (c_level4_entity);
CREATE INDEX CONCURRENTLY IF NOT EXISTS case_c_level5_entity_idx ON nap."case" USING btree (c_level5_entity);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_nap_case_platform_status_entity ON nap."case" USING btree (c_acq_platform, c_case_sta, c_level4_entity, c_level5_entity);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_entity_platform ON nap."case" USING btree (c_level4_entity, c_acq_platform, i_case_id) INCLUDE (i_case_action_max_seq, c_case_sta, c_tr_type, c_case_final_liability);
```

US load has no index that prod lacks.

### A4. Refresh statistics after A1–A3 (load)
UK `action` was last analyzed 2026-04-03 (load B1.1: 9.6 M estimated rows against 13.2 M live). `ANALYZE` updates planner statistics only; it does not change data. VACUUM is deliberately not included: it would raise the all-visible share well above prod's, see "Visibility limit".
```sql
ANALYZE wdp.action;
ANALYZE wdp."case";
ANALYZE wdp.case_lock;
ANALYZE nap.action;
ANALYZE nap."case";
ANALYZE nap.case_lock;
```
Then re-run FINALIZE B1.2 in load. Compared with prod B1.2, it should differ only in sizes and scan counts, plus the A3 indexes if you kept them.

### A5. US (`wdp`): foreign key `action.i_case_id → case.i_case_id` (load only)
Load B1.3 (2026-11-12) shows **no constraints at all on `wdp.action`**. Prod has `fk_case` there (prod B1.3), and load UK has its `fk_case`.
- **Why it matters:** PostgreSQL uses validated foreign keys to estimate how many rows a join on those columns returns. Old and new activities SQL both join `action` to `case` on `i_case_id`, so without the key, load US join estimates, and with them plans, can differ from prod's.
- Primary-key and unique *constraints* are not needed. The unique indexes from A1/A2 give the planner the same uniqueness, so only the foreign key is added.
- The key is added `NOT VALID` first, which is quick, then validated. Validation reads both tables but lets reads and writes continue. The planner only uses a key once it is validated.

**A5.0 Pre-check (read-only):** `action` rows whose case does not exist. Must be **0**, or validation fails. **Run time class:** medium (anti-join of 26.7 M actions to 6.7 M cases).
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15min';
SET LOCAL lock_timeout = '5s';
SELECT count(*) AS orphan_actions
FROM wdp.action a
WHERE NOT EXISTS (SELECT 1 FROM wdp."case" c WHERE c.i_case_id = a.i_case_id);
```
Then run `ROLLBACK;`. If `orphan_actions` > 0, stop and send me the count. The options would be to keep the key `NOT VALID` (ignored by the planner, so no gain) or to skip A5.

**A5.1 Add and validate.** Run with auto-commit on, one statement at a time, after A5.0 returns 0:
```sql
SET statement_timeout = 0;
SET lock_timeout = '5s';
ALTER TABLE wdp.action ADD CONSTRAINT fk_case FOREIGN KEY (i_case_id) REFERENCES wdp."case" (i_case_id) NOT VALID;
ALTER TABLE wdp.action VALIDATE CONSTRAINT fk_case;
```
Check: re-run FINALIZE B1.3. `wdp.action fk_case` should show `validated = true`.

**Rollback (A5):**
```sql
ALTER TABLE wdp.action DROP CONSTRAINT IF EXISTS fk_case;
```

---

## Verifying each index (writer, then every read replica)

Replace `<schema>` / `<index>`.

```sql
-- V1 build progress (writer, while CREATE INDEX CONCURRENTLY runs)
SELECT pid, phase, blocks_done, blocks_total, tuples_done, tuples_total
FROM pg_stat_progress_create_index;

-- V2 valid and ready (writer and each replica): expect indisvalid = t, indisready = t, indislive = t
SELECT n.nspname, c.relname, i.indisvalid, i.indisready, i.indislive,
       pg_size_pretty(pg_relation_size(i.indexrelid)) AS size,
       pg_get_indexdef(i.indexrelid)                   AS definition
FROM pg_index i
JOIN pg_class c     ON c.oid = i.indexrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = '<schema>' AND c.relname = '<index>';

-- V3 any INVALID leftovers from a failed build (should return 0 rows)
SELECT n.nspname, c.relname
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('wdp','nap') AND NOT i.indisvalid;

-- V4 being used: snapshot on each READ REPLICA (counters are per instance), again after the new path is live
SELECT now(), schemaname, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch, last_idx_scan
FROM pg_stat_user_indexes
WHERE schemaname = '<schema>' AND indexrelname = '<index>';
```

V5 is the plan check: Stage 4 (`GDP-ACTIVITIES-PHASE1-VERIFY-SQL.md`) runs EXPLAIN (ANALYZE, BUFFERS) on the new count and page SQL. Expected driving nodes:

| Index | Expected in plans |
|---|---|
| US-1 / US-2 / UK-4 / UK-5 | `Index Scan` / `Index Only Scan` / `Bitmap Index Scan using ix_case_level{4,5}_entity_scope`, with `c_acq_platform` and `c_case_sta` in the Index Cond |
| US-3 / UK-6 | `Index (Only) Scan using ix_action_case_processed{_est}_sta`, with `i_case_id`, the date range and `c_action_sta` in the Index Cond (seen in the local syntax check) |
| UK-7 | `Index (Only) Scan using ix_action_processed_actionid_sta`, date range in the Index Cond, ordered output (no Sort node on a date-first page) |

For index-only scans, read `Heap Fetches` in the plan. A high share means the visibility map is behind (next section), not that the index is wrong.

## Visibility limit (report §6)

Index-only reads skip the heap only on pages marked all-visible. Only VACUUM sets that mark.
- US `action` was 100 % all-visible at its last vacuum (2026-11-05), yet 51 % of the 60-day rows in plan #7 needed a heap fetch: the newest rows are the unmarked ones, and activities reads mostly recent dates.
- UK `action` is 64 % and UK `case` 62 % all-visible. The last autovacuum was 2026-10-15 (`action`) and 2026-10-30 (`case`).
- **Effect on these indexes:**
  - The key-column filters (platform, status) still prune before any heap visit; that is the reason for the key choice above.
  - The INCLUDE outputs (`i_case_id`, `i_action_id`) avoid the heap only on all-visible pages.
  - So for recent windows, US-3, UK-6 and UK-7 will often run as index scans that still fetch heap rows. They are better than today, but not fully index-only.
- **What would improve it (DBA, D10):** more frequent autovacuum on UK `action` and `case`, and on the recent part of US `action`, for example lower `autovacuum_vacuum_insert_scale_factor` or `autovacuum_vacuum_scale_factor` for these tables.
  - These are per-table storage parameters (`ALTER TABLE … SET (...)`). The brief limits Phase 1 to indexes only, so **no script is given**.
  - It is listed so the DBA can decide under D10.

---

## Candidates to retire — verify replica usage first (no drop scripts)

From report §2. The scan counters are writer-only and cumulative since an unknown date (`stats_reset` NULL). Run V4 above for each candidate on **every replica** over a representative period before deciding.

| Region | Index | Key columns | Size | Writer scans | Why it is a candidate |
|---|---|---|---|---|---|
| US | `wdp.ix_action_dispute_amt` | a_dispute_amt, i_case_id | 3,714 MB | 0 | zero scans |
| UK | `nap.ix_action_dispute_amt` | a_dispute_amt, i_case_id | 923 MB | 0 | zero scans |
| UK | `nap.ix_case_i_case_i_acq_refnce_num` | i_case, i_acq_refnce_num | 431 MB | 0 | zero scans |
| US | `wdp.ix_case_i_case` | i_case | 1,001 MB | 182,713,126 | same key as unique `pin_case_i_case_key` (the unique index would take over its scans) |
| UK | `nap.ix_case_i_case` | i_case | 211 MB | 48,606,201 | same key as unique `case_i_case_key` |
| US | `wdp.idx_case_lock_i_case` | i_case | 312 kB | 1,075,008,464 | same key as unique `case_lock_unique` |
| UK | `nap.idx_case_lock_i_case` | i_case | 160 kB | 362,795,465 | same key as unique `case_lock_unique` |
| UK | `nap.ix_action_i_case_i_action_seq` | i_case, i_action_seq | 944 MB | 5,220,000 | same key as unique `uq_action_case_action_seq` |

The duplicate-key indexes have high scan counts because the planner picks either copy. Dropping one moves its scans to the unique twin; it does not remove the access path.

Not listed, but worth noting for later: once US-3 and UK-6 exist, they lead on `i_case_id`, as `ix_action_case_id` does. That index is the hottest in both regions (25 billion US / 41 billion UK scans) and is far narrower. **Do not** treat it as redundant without measurement.

## Differences from the findings file

- **Key vs INCLUDE.** Findings D8 says "carrying"; the brief asks which. Platform, case status and action status are key columns here, not INCLUDE, for the reasons above (filtering before the heap with poor visibility; bitmap scans for the current OR predicate).
- **Optional partial variants.** The partial versions of US-2, US-3 (and UK-5) are offered as options only. Neither the findings nor the brief asked for them, and no production index is partial today.

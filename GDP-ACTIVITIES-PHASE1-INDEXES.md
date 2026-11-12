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

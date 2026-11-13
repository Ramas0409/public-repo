# GDP-ACTIVITIES-PHASE1B-SQL.md
**Phase 1B run pack (change scope): setup A gap, restore the Stage 2 indexes, VACUUM, setup C (new code's SQL), equivalence**
*2026-11-13. LOAD only. Nothing here runs in production. I have no database access; you run everything. The earlier Stage 1 pack (setups A + B, pickers) is kept as `GDP-ACTIVITIES-PHASE1B-SQL-STAGE1.md`.*

---

## 0. Where load is now, and what the saved files belong to

**Load state (from you):** the 7 Stage 2 indexes were present for steps 1-3 of the Stage 1 pack, then **dropped**. Nothing has run since.

| File in `docs/results-phase1b/` | Taken with Stage 2 indexes | Setup | What it is |
|---|---|---|---|
| `p1b-vacuum.txt` | present | none (maintenance) | VACUUM (ANALYZE) of the 4 tables |
| `p1b-visibility.txt` | present | none (catalog) | all-visible %, last vacuum / analyze |
| `p1b-us-consumers.txt` | n/a (picker reads `acl` + `case`) | none | US test consumers, counts only |
| `p1b-uk-consumers.txt` | n/a | none | UK test consumers, counts only |

- **No setup A, B or C cell has been captured in Phase 1B.** All four files are maintenance, catalog or picker output.
- **Setup A cells that exist:** Phase 1 `docs/results-phase1/load/b3-{us,uk}-{median,largest}-{2d,60d}-{count,page}.txt` (old SQL, no Stage 2 indexes).
- **Setup A cells missing:** the **deep page, largest consumer, 60 d**, per region (US offset ≈ 7,400; UK ≈ 62,600). It was never measured on the old SQL.
- **Recommendation (step 1 below): re-run all 9 setup A cells per region, not only the 2 deep pages.** The `b3-*` files were measured *before* the VACUUM, when UK `action` was 0 % all-visible. Load is now ≈ 100 % all-visible, so setup C would be compared with an old-SQL baseline taken under different table state. One script per region does all 9 (≈ 1-3 min each). If you only want the gap filled, the deep-page cell is the last block in each script; I would then use `b3-*` for the other 8 and flag the visibility difference next to every ratio.

---

## 1. Order of operations

| # | Step | Writes? | Section | Output |
|---|---|---|---|---|
| 1 | **Setup A** (old SQL, indexes **absent**, i.e. now): `p1b-us-a.psql`, then `p1b-uk-a.psql` | no | §3 | 18 files `a-*.txt` (raw) |
| 2 | **Recreate the Stage 2 indexes:** I1 check, I2 drop INVALID leftovers (only if any), I3 create the missing ones, I4 validity check | **yes** | §4 | `p1b-index-check-before.txt`, `p1b-index-check-after.txt` |
| 3 | **VACUUM (ANALYZE)** the 4 tables | **yes** (maintenance) | §5 | `p1b-vacuum-2.txt` (optional) |
| 4 | **Setup C** (new SQL; only when I4 shows 7 valid): `p1b-us-c.psql`, then `p1b-uk-c.psql` | no | §3 | 18 files `c-*.txt` (raw) |
| 5 | **Equivalence:** `p1b-us-equiv.psql`, then `p1b-uk-equiv.psql` | no | §3 | 14 files `eq-*.txt` (counts and true/false only) |
| 6 | **STOP.** Copy the files (§6), tell me **"results ready"** | | | |

- Do not run step 4 or 5 until I4 shows **7 rows, all `exists = t`, `indisvalid = t`, `indisready = t`**.
- Between steps 1 and 5, avoid other load activity that writes to these tables. Note it if any happens.
- Every script can be re-run; it overwrites its own files.
- **Deferred to the change release (not run here):** setup B (old SQL with indexes), the wider matrix (small/large consumers, 1/7/30 d), concurrency, cold runs, write cost of the 7 indexes.

---

## 2. How to run the scripts

- **Copy** `docs/phase1b-scripts/p1b-{us,uk}-{a,c,equiv}.psql` (6 files) to `C:\test\` on the load machine, next to your vars file `C:\test\p1b-vars.psql`. Create `C:\test\p1b\` if it is not there.
- **Variables at the top of each script** (edit only if your paths differ):
  ```
  \set varsfile 'C:/test/p1b-vars.psql'
  \set outdir 'C:/test/p1b'
  ```
  The script then does `\i :varsfile`, and stops with a message if `:{us,uk}_{median,largest}_pred` is missing. The scripts themselves contain **no entity values**. They use `:us_median_plat`, `:us_median_pred`, `:us_largest_plat`, `:us_largest_pred` (UK the same with `uk_`), which your vars file already defines.
- **Run** with psql connected to load (writer; load has no replica):
  ```
  "C:\Program Files\PostgreSQL\16\bin\psql.exe" "<your load connection string>"
  \i C:/test/p1b-us-a.psql
  ```
  pgAdmin's PSQL Tool works too, once Preferences → Paths → Binary paths points at an existing `bin` folder (the OneDrive path it has now does not exist).
- **What each statement does:** runs in its own `BEGIN TRANSACTION READ ONLY` (`statement_timeout` 10 min, `lock_timeout` 5 s). It runs **twice**: run 1 goes to `_warmup.txt` (discard), run 2 goes to its own file, whose first line says `# setup=… cell=… run=2 of 2`.
- **Deep page:** each script first runs that setup's count to get the total, then pages at offset `((total / 200) − 1) × 200`. The offset and total are written on the file's first line. If the total is below 400, the file says `n/a`.
- **Dates** (same as Phase 1): US `TO` 2026-10-05, 2 d `FROM` 2026-10-04, 60 d `FROM` 2026-08-06; UK `TO` 2025-09-30, 2 d `FROM` 2025-09-29, 60 d `FROM` 2025-08-01.
- **UK page in load** leaves out `customerField1/2/3` (columns absent in load `nap.case`), old and new alike. Display columns only.
- **Where the SQL comes from:** printed from the code (old: the unchanged DAO builders; new: `ActivitiesSqlBuilder` / `ActionTableKeySource` after this change). The parameters are written in as literals; the SQL text is otherwise the app's.
- **Checked locally** (PostgreSQL 17.10, throwaway schemas, then dropped): all 6 scripts ran with 0 errors, 50 files written, deep-page and `n/a` branches both exercised, all 13 equivalence cells `t`.

---

## 3. Script files and result files

| Step | Script (`docs/phase1b-scripts/`) | Result files in `C:\test\p1b\` |
|---|---|---|
| 1 | `p1b-us-a.psql` | `a-us-median-2d-count.txt`, `a-us-median-2d-page.txt`, `a-us-median-60d-count.txt`, `a-us-median-60d-page.txt`, `a-us-largest-2d-count.txt`, `a-us-largest-2d-page.txt`, `a-us-largest-60d-count.txt`, `a-us-largest-60d-page.txt`, `a-us-largest-60d-page-deep.txt` |
| 1 | `p1b-uk-a.psql` | the same 9 names with `a-uk-` |
| 4 | `p1b-us-c.psql` | the same 9 names with `c-us-` |
| 4 | `p1b-uk-c.psql` | the same 9 names with `c-uk-` |
| 5 | `p1b-us-equiv.psql` | `eq-us-median-2d.txt`, `eq-us-median-60d.txt`, `eq-us-largest-2d.txt`, `eq-us-largest-60d.txt`, `eq-us-median-60d-max.txt`, `eq-us-largest-60d-max.txt`, `eq-us-largest-60d-deep.txt` |
| 5 | `p1b-uk-equiv.psql` | the same 7 names with `eq-uk-` |

- **Timing files** (`a-*`, `c-*`) are `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)` plans. Their `Filter` / `Index Cond` lines contain entity values, so they are raw: they go to `images/p1b/`, never `docs/`.
- **Equivalence files** (`eq-*`) hold one row: `cell | old_total | new_total | totals_match | old_rows | new_rows | keys_match | rows_match`. Counts and `t`/`f` only. These can go straight into `docs/results-phase1b/`.
  - `keys_match`: the page's action ids, in order, are identical.
  - `rows_match`: an md5 of every output row (all columns, page order) is identical. The old and new page are computed in the same statement, so `CURRENT_DATE`-based sort columns agree.
  - `-max` cells: the `maxAction = true` form (latest matching action per case).

**Proof bar used in the report** (stated now, before the numbers): a timing cell **PASSES** if new (setup C) execution time ≤ old (setup A) × 1.10 + 5 ms. The 10 % + 5 ms margin is an assumed allowance for run-to-run noise on a warm, quiet load; no separate noise runs are made in this scope (the 5-run noise files were part of the dropped setup B work). Equivalence passes only if every `*_match` is `t`.

---

## 4. Step 2: recreate the 7 Stage 2 indexes (LOAD only)

### I1. Check what exists now (save as `p1b-index-check-before.txt`)
```sql
SELECT x.schema_name, x.index_name,
       c.oid IS NOT NULL                AS exists,
       i.indisvalid, i.indisready,
       pg_size_pretty(pg_relation_size(c.oid)) AS size,
       pg_get_indexdef(c.oid)           AS definition
FROM (VALUES ('wdp','ix_case_level4_entity_scope'),
             ('wdp','ix_case_level5_entity_scope'),
             ('wdp','ix_action_case_processed_est_sta'),
             ('nap','ix_case_level4_entity_scope'),
             ('nap','ix_case_level5_entity_scope'),
             ('nap','ix_action_case_processed_sta'),
             ('nap','ix_action_processed_actionid_sta')) AS x(schema_name, index_name)
LEFT JOIN pg_namespace n ON n.nspname = x.schema_name
LEFT JOIN pg_class c     ON c.relnamespace = n.oid AND c.relname = x.index_name
LEFT JOIN pg_index i     ON i.indexrelid = c.oid
ORDER BY x.schema_name DESC, x.index_name;
```
Expected now: 7 rows, `exists = f` (you dropped them). For each row:
- `exists = f` → create it in I3.
- `exists = t`, `indisvalid = f` → INVALID leftover: drop it in I2, then create it in I3. (`CREATE INDEX … IF NOT EXISTS` would skip an INVALID index, so the drop is needed.)
- `exists = t`, `indisvalid = t` → leave it; skip its I3 statement. If its `definition` differs from I3, **stop and tell me**.

### I2. Drop INVALID leftovers (only the rows I1 shows as `exists = t, indisvalid = f`)
One statement at a time, auto-commit on (not inside a transaction):
```sql
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_case_level4_entity_scope;
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_case_level5_entity_scope;
DROP INDEX CONCURRENTLY IF EXISTS wdp.ix_action_case_processed_est_sta;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_case_level4_entity_scope;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_case_level5_entity_scope;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_action_case_processed_sta;
DROP INDEX CONCURRENTLY IF EXISTS nap.ix_action_processed_actionid_sta;
```

### I3. Create the missing ones (exactly as in `GDP-ACTIVITIES-PHASE1-INDEXES.md`, US-1…UK-7)
One statement at a time, auto-commit on. `CONCURRENTLY` cannot run inside a transaction block.
```sql
-- session settings (as in the indexes doc)
SET statement_timeout = 0;
SET lock_timeout = '5s';        -- fail fast if the SHARE UPDATE EXCLUSIVE lock is blocked; retry later
-- SET maintenance_work_mem = '...';   -- DBA to size

-- US-1
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level4_entity_scope
    ON wdp."case" (c_level4_entity, c_acq_platform, c_case_sta)
    INCLUDE (i_case_id);

-- US-2
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level5_entity_scope
    ON wdp."case" (c_level5_entity, c_acq_platform, c_case_sta)
    INCLUDE (i_case_id);

-- US-3
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_case_processed_est_sta
    ON wdp.action (i_case_id, d_action_processed_est, c_action_sta)
    INCLUDE (i_action_id);

-- UK-4
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level4_entity_scope
    ON nap."case" (c_level4_entity, c_acq_platform, c_case_sta)
    INCLUDE (i_case_id);

-- UK-5
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_case_level5_entity_scope
    ON nap."case" (c_level5_entity, c_acq_platform, c_case_sta)
    INCLUDE (i_case_id);

-- UK-6
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_case_processed_sta
    ON nap.action (i_case_id, d_action_processed, c_action_sta)
    INCLUDE (i_action_id);

-- UK-7
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_action_processed_actionid_sta
    ON nap.action (d_action_processed, i_action_id, c_action_sta)
    INCLUDE (i_case_id);
```
- Progress while a build runs: `SELECT pid, phase, blocks_done, blocks_total, tuples_done, tuples_total FROM pg_stat_progress_create_index;`
- If a build fails or is cancelled, it leaves an INVALID index: run I1, then I2 for that index, then its I3 statement again.

### I4. Validity check (save as `p1b-index-check-after.txt`)
Run **I1 again**. Required before step 3: **7 rows, every one `exists = t`, `indisvalid = t`, `indisready = t`**, and each `definition` matching I3. Also run:
```sql
-- any INVALID index left in either schema (expect 0 rows)
SELECT n.nspname, c.relname
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('wdp','nap') AND NOT i.indisvalid;
```
If either check fails, **stop and tell me**; do not run setup C.

The 12 load-only UK indexes from the earlier step (A0-A5 in the indexes doc) and every other index stay as they are.

---

## 5. Step 3: VACUUM (ANALYZE) after the indexes are back (LOAD only)
Refreshes visibility and planner statistics with the 7 indexes in place, so setup C plans against current stats. Not FULL: no rewrite, no exclusive lock. One statement at a time, auto-commit on:
```sql
VACUUM (ANALYZE, VERBOSE) wdp."case";
VACUUM (ANALYZE, VERBOSE) wdp.action;
VACUUM (ANALYZE, VERBOSE) nap."case";
VACUUM (ANALYZE, VERBOSE) nap."action";
```
Saving the VERBOSE output as `p1b-vacuum-2.txt` is optional (no entity values in it).

---

## 6. Checklist (then STOP and say "results ready")

**Straight into `docs/results-phase1b/`** (no entity values):
- [ ] `p1b-index-check-before.txt` (I1, before any drop/create)
- [ ] `p1b-index-check-after.txt` (I1 again + the INVALID query)
- [ ] `p1b-vacuum-2.txt` (optional)
- [ ] 14 `eq-*.txt` (7 US + 7 UK)

**Raw, to `gdp-search/images/p1b/`** (I redact into `docs/results-phase1b/` under the same names):
- [ ] 18 `a-*.txt` (9 US + 9 UK), from step 1, indexes absent
- [ ] 18 `c-*.txt` (9 US + 9 UK), from step 4, indexes valid, after VACUUM

**Also tell me:**
- [ ] whether anything else wrote to these tables between steps 1 and 5;
- [ ] any script message other than the cell names and `done:` lines.

Do not copy `_warmup.txt` or your vars file anywhere.

---

## 7. Re-run after the F-3 page-ordering fix (2026-11-13)

**Decision:** F-1 and F-2 accepted. F-3 fixed in both regions: the page keys are now ordered by `(activity date + interval '0'), action id`. That is the same order as before, but no index can supply it, so the page never uses the early-stopping date walk. Count SQL is unchanged, so the count cells from setup C are reused.

**Load state needed:** the same as setup C. The 7 Stage 2 indexes are present and valid; nothing else changes. No VACUUM is needed.

| Script (`docs/phase1b-scripts/`) | Result files in `C:\test\p1b\` |
|---|---|
| `p1b-us-c2.psql` | `c2-us-median-2d-page.txt`, `c2-us-median-60d-page.txt`, `c2-us-largest-2d-page.txt`, `c2-us-largest-60d-page.txt`, `c2-us-largest-60d-page-deep.txt` (raw) and `eq2-us-median-2d.txt`, `eq2-us-median-60d.txt`, `eq2-us-largest-2d.txt`, `eq2-us-largest-60d.txt`, `eq2-us-median-60d-max.txt`, `eq2-us-largest-60d-max.txt`, `eq2-us-largest-60d-deep.txt` |
| `p1b-uk-c2.psql` | the same 12 names with `-uk-` |

- **Run** in a fresh PSQL Tool window, the same way as before:
  ```
  \i C:/test/p1b-us-c2.psql
  \i C:/test/p1b-uk-c2.psql
  ```
  Each ends with `done: 5 files c2-… + 7 files eq2-…`.
- Same rules as before: each statement runs twice and only run 2 is saved. The variables at the top of each script point to `C:/test/p1b-vars.psql` and `C:/test/p1b`.
- **Checked locally** (throwaway schemas, then dropped): 0 errors, 24 files, every equivalence row `t`.
- **Then:**
  - [ ] Copy the 10 `c2-*` files (raw) and the 14 `eq2-*` files into `gdp-search/images/p1b/`.
  - [ ] Say **"results ready"**.

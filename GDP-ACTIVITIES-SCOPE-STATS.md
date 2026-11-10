# GDP-ACTIVITIES-SCOPE-STATS.md
**Global Dispute Platform (GDP) — Activity search scope sizing: mapping chain, sizing queries, results template**
*Prepared 2026-11-10. Read-only analysis. No query in this file has been run; there is no data access from the workspace.*

> **Source-integrity caveat.** The two service snapshots in `gdp-search/` appear transcribed (literal `[unclear]` tokens) and neither compiles as checked in; both are untracked in the `wdp-workspace` monorepo (`origin` = `https://github.com/Ramas0409/wdp-workspace.git`, HEAD `e0458635c49f97c48804a59a2a6c771fa5e1c0c8`), so no commit SHA identifies them. See `GDP-SEARCH-GAPS.md` G1–G3. Step 1 is what the snapshot says.

**Citation legend** (paths relative to `gdp-search/`)
- `J/` = `gcp-chargeback-service/src/main/java/com/wp/gcp/chargeback/` (COMP-21)
- `C27/` = `gcp-case-search-service/main/java/com/wp/gcp/casesearch/` (COMP-27)
- `SQL/` = `case-serarch-sqls/`

---

## 1. Mapping chain (Step 1)

### 1.1 Entry points that reach COMP-27 activities search

All three COMP-21 activities endpoints reach COMP-27 `POST /{region}/chargeback/cases/search` through `WDPRestInvoker.searchWDPChargebackCase` (US: `J/service/impl/USCaseDetailsServiceImpl.java:162`; UK: `J/service/impl/UKCaseDetailsServiceImpl.java:166`). Which callers get there, and with what scope:

| COMP-21 endpoint | Region | Reaches COMP-27 when | Does not reach COMP-27 when | Cite |
|---|---|---|---|---|
| `POST /cases/activities` | US | `entitlementFlag` false **and** `disputesMigrationStatus` true **and** (partner **or** ACL entity types include Chain/SuperChain and **not** OrgID) | `entitlementFlag` true → DS; migration false → DS; ACL has OrgID **and** Chain/SuperChain → empty 200, no query; ACL has only OrgID → DS | `J/router/impl/CaseActivitiesRouterImpl.java:34-39, 64-67, 70-90, 100-108` |
| `POST /us/cases/activities` | US | Always (no migration gate, no entity-type routing) | — | `J/controller/USCaseController.java:99-113` |
| `POST /nap/cases/activities` | UK | Always | — | `J/controller/UKCaseController.java:91-104` |

So on `/cases/activities` an `OrgID` value never reaches COMP-27, but on `/us/cases/activities` it can.

**ACL fetch.** Non-partner callers only: `invokeDataService.getEntityDetails(consumerName, "ACTIVE", …)` (`J/router/impl/CaseActivitiesRouterImpl.java:59-62`; `J/controller/USCaseController.java:107-109`; `J/controller/UKCaseController.java:98-100`; status constant `J/utils/ApplicationConstants.java:87`; HTTP call `J/wsclient/impl/WDPRestInvoker.java:197-222`).
- That UAMS `/acl/search` reads `wdp.acl` and maps `status: ACTIVE` to `c_status = 'ACTIVE'` comes from the task brief. UAMS source is **not in the workspace**, so this is **not determinable from source**.
- `wdp.acl` columns: `SQL/wdp.acl:5-22`. Its only secondary index is `(c_consumer_name, c_status)` (`SQL/wdp.acl:32-37`).

**Partners** (`SIGNIFYD`, `JUSTTAI`, case-insensitive; `J/utils/CaseServiceSupport.java:480-483`):
- Partners make no ACL call, so they send no entity filter.
- Platform is the full region list: US `[CORE, PIN, VAP]`, UK `[NAP, LATAM]` (`J/utils/CaseServiceSupport.java:468-475`).
- `productType` is `SIGNIFYD` or `DEFENDER` (`J/utils/CaseServiceSupport.java:491-499`; `J/utils/ApplicationConstants.java:146-147`). COMP-27 applies it as `C.C_PRODUCT_TYPE = ?` (`C27/dao/impl/USDisputeCaseSearchDAOImpl.java:554-556`; `C27/dao/impl/UKDisputeCaseSearchDAOImpl.java:538-540`).
- A partner's scope is therefore "whole platform × product type", not an ACL scope. The queries below do not size it (see §2.4 note).

**Entitlement mode** (`entitlementFlag` true) takes its entities from the entitlement API rather than `wdp.acl` (`J/service/impl/USCaseDetailsServiceImpl.java:198-200`; `J/service/impl/UKCaseDetailsServiceImpl.java:203-205`). `wdp.acl`-based sizing does not cover it.

### 1.2 ACL row → entity code → case column

COMP-21 filters the ACL rows by region (`J/utils/ACLUtils.java:94-139`). It then groups the kept rows' `c_entity_value` by `c_entity_type` and maps each type to a code via `EntitlementEntities.getByEntityType` (`J/utils/ACLUtils.java:37-53`; `J/enums/EntitlementEntities.java:13-19, 32-34`). COMP-27 turns each code into an OR-ed `IN (...)` on a case column.

| Region | ACL `c_source_system` kept | ACL `c_entity_type` kept | Code sent | COMP-27 case column | COMP-27 cite |
|---|---|---|---|---|---|
| US | `CORE`, `VAP`, `PIN` (case-insensitive; PIN check is noted in code as dead) | `Chain` | `CH` | `C.C_LEVEL4_ENTITY` | `C27/dao/impl/USDisputeCaseSearchDAOImpl.java:795-798` |
| US | same | `SuperChain` | `SC` | `C.C_LEVEL5_ENTITY` | `…USDisputeCaseSearchDAOImpl.java:791-794` |
| US | same | `OrgID` | `ORG` | `C.C_LEVEL4_ENTITY` | `…USDisputeCaseSearchDAOImpl.java:825-829` |
| UK | `NAP`, `LATAM` (case-insensitive) | `Company ID` | `CO` | `C.C_LEVEL4_ENTITY` | `C27/dao/impl/UKDisputeCaseSearchDAOImpl.java:739-742` |
| UK | same | `Group ID` | `GR` | `C.C_LEVEL5_ENTITY` | `…UKDisputeCaseSearchDAOImpl.java:743-747` |
| UK | same | `Outlet ID` | `OL` | `C.C_LEVEL2_ENTITY` | `…UKDisputeCaseSearchDAOImpl.java:748-752` |

Filter cites: US `J/utils/ACLUtils.java:115-131`; UK `J/utils/ACLUtils.java:97-109`. If no row is kept for the region, COMP-21 returns 403 before calling COMP-27 (`J/utils/ACLUtils.java:111-114, 133-136`).

Facts that affect the sizing queries:
1. **Source system matching is case-insensitive** (`Strings.CI.equalsAny`, `J/utils/ACLUtils.java:106-107, 125-129`). **Entity type matching is case-sensitive** (`Set.contains`, `:108, :130`; `HashMap` lookup, `J/enums/EntitlementEntities.java:24-34`). The queries copy this: `upper(c_source_system)` but an exact `c_entity_type`.
2. **Values are not de-duplicated** (`Collectors.toList()`, `J/utils/ACLUtils.java:43`). If the same value appears twice in the ACL, it appears twice in the IN list.
3. **The whole entity block is one OR group** wrapped in `AND ( … )` (`C27/dao/impl/USDisputeCaseSearchDAOImpl.java:789, 832`; `C27/dao/impl/UKDisputeCaseSearchDAOImpl.java:717, 760`). A case matches if any one level column matches. `CH` and `ORG` both hit `C_LEVEL4_ENTITY` (US), and `CO` hits the same column in the UK.
4. **Platform list** (`J/utils/ACLUtils.java:55-69`). It is the distinct **raw** `c_source_system` values of the kept rows. `PIN` is added only if that set contains exactly `CORE`, a case-sensitive `Set.contains` (`:63-65`). COMP-27 applies it as `C.C_ACQ_PLATFORM IN (...)`: US `C27/dao/impl/USDisputeCaseSearchDAOImpl.java:498-503`, UK `C27/dao/impl/UKDisputeCaseSearchDAOImpl.java:481-486`.
5. **The UK `merchantId` request list** is applied as a separate AND `C.C_LEVEL1_ENTITY IN (...)` (`…UKDisputeCaseSearchDAOImpl.java:763-767`; US `:835-839`). It narrows scope and does not come from the ACL.

### 1.3 Activities query shape and static filters (COMP-27)

COMP-27 entry: `C27/controller/ChargebackCaseSearchController.java:49-79` (internal-issuer gate `:66-69`; validation `:70`; `platform` → `caseSource` `:72`) → `C27/service/impl/ChargebackCaseSearchServiceImpl.java:49-101`.

| Item | US (`wdp`) | UK (`nap`) | Cite |
|---|---|---|---|
| Tables | `wdp.case c INNER JOIN wdp.action a ON c.I_CASE_ID = a.I_CASE_ID LEFT JOIN wdp.case_lock cl ON c.i_case = cl.i_case` | `nap."case" c INNER JOIN nap."action" a ON c.I_CASE_ID = a.I_CASE_ID LEFT JOIN nap.case_lock cl ON c.i_case = cl.i_case` | US `…USDisputeCaseSearchDAOImpl.java:417-422`; UK `…UKDisputeCaseSearchDAOImpl.java:429, 438` |
| Rows counted | `SELECT count(*) FROM LATEST_ACTIONS` over (case, action) rows. Activities requests do not set `maxAction`, so every matching action counts, not just the latest per case. | same | US `…USDisputeCaseSearchDAOImpl.java:54-58`; UK `…UKDisputeCaseSearchDAOImpl.java:59-64`; `maxAction` read `C27/service/impl/ChargebackCaseSearchServiceImpl.java:57`; COMP-21 sets it only on case-list search (`J/service/impl/USCaseDetailsServiceImpl.java:98`; `J/service/impl/UKCaseDetailsServiceImpl.java:102`), not in the activities builders (`…USCaseDetailsServiceImpl.java:165-211`; `…UKCaseDetailsServiceImpl.java:170-222`) |
| Platform | `C.C_ACQ_PLATFORM IN (<platform list>)` | same | §1.2 item 4 |
| Case status | `C.c_case_sta IN ('OPEN','CLOSED')`, because the chargeback mapping never sets `caseStatus` | same | US `…USDisputeCaseSearchDAOImpl.java:504-511`; UK `…UKDisputeCaseSearchDAOImpl.java:488-495`; mapping `C27/service/impl/ChargebackCaseSearchServiceImpl.java:170-205` |
| Action status (default) | `A.c_action_sta IN ('OPEN','ERROR','CLOSED')` | same | US `…USDisputeCaseSearchDAOImpl.java:884`; UK `…UKDisputeCaseSearchDAOImpl.java:822-824` |
| Action status (`actionable=true`) | `A.c_action_sta IN ('OPEN')` plus owner | same | COMP-21 `…USCaseDetailsServiceImpl.java:185-188`, `…UKCaseDetailsServiceImpl.java:190-193`; COMP-27 mapping `…ChargebackCaseSearchServiceImpl.java:176-177`; UK `…UKDisputeCaseSearchDAOImpl.java:816-820` |
| **Date column** | `A.D_ACTION_PROCESSED_EST BETWEEN ? AND ?` | `A.D_ACTION_PROCESSED BETWEEN ? AND ?` | US `…USDisputeCaseSearchDAOImpl.java:929-939`; UK `…UKDisputeCaseSearchDAOImpl.java:869-874` |
| Date range | Request `from`/`to`. COMP-27 rejects `DAYS.between(from,to) > 60`, so the inclusive window is up to 61 calendar days. COMP-21's own limit reads `days > 60[unclear]` and is **not determinable from source**. | same | `C27/util/RequestValidator.java:410-413, 428-441`; `J/utils/DisputesValidator.java:369` |
| UK only | — | `A.C_MIGRATION_STA = 'Y'` when the request has `wdpMigrationStatus=true` | COMP-21 `…UKCaseDetailsServiceImpl.java:208-212`; COMP-27 `…UKDisputeCaseSearchDAOImpl.java:844-847` |

**US date-column ambiguity (recorded as found):**
- Code filters on `D_ACTION_PROCESSED_EST` (`…USDisputeCaseSearchDAOImpl.java:933`).
- The comment directly above says `// A.D_ACTION_PROCESSED` (`:930`).
- A commented-out alternative uses `CASE WHEN C_ACQ_PLATFORM IN ('CORE','PIN') THEN D_ACTION_PROCESSED_EST ELSE D_ACTION_PROCESSED END` (`:934-938`).
- Sorting also swaps the column to `_EST` (`:1155-1156`).
- The column is **absent** from `SQL/action.sql`, which has `d_action_processed date` at `:17`. It is added only by the proposal script `SQL/d_action_processed_est-trigger-backfill.sql:1` (`date`).

Whether the column exists, and is populated, in each environment is **not determinable from source** (`GDP-SEARCH-GAPS.md` G9). Queries c and e are therefore given for both columns.

**Index facts relevant to cost (folder DDL only; recorded, not recommended):**
- `wdp.case` has no index on `c_level4_entity`, `c_level5_entity`, `c_acq_platform` or `c_case_sta` (index list `SQL/case.sql:188-260`). It does have `ix_case_level2_entity` (`:228-230`).
- `wdp.action` has btree indexes leading on `d_action_processed`: `ix_action_processed_actionid_case` (`SQL/action.sql:170-172`) and, under `c_action_sta, c_ownr`, `ix_action_status_owner_processed_actionid_case` (`:202-204`). There is none on `d_action_processed_est`.
- `nap` DDL is not in the workspace, so `nap` index coverage is **not determinable from source**.

**Not determinable from source:**
- whether `nap.case.c_acq_platform` holds exactly `NAP` / `LATAM`;
- whether `wdp.case` holds VAP cases;
- the `nap` column types.

Query q0 checks the column facts read-only.

---

## 2. Queries (Step 2)

### 2.1 Rules applied to every query
- `SELECT` only. No writes, no temp tables, no explicit locks. Run inside a read-only transaction.
- Output is counts and percentiles only. No query returns an entity value, consumer name or merchant name. Entity values appear only inside CTEs and are aggregated before output.
- ACL: `c_status = 'ACTIVE'` only.
- Static filters as in §1.3:
  - platform: US `('CORE','PIN')`, UK `('NAP','LATAM')`; in query d, each consumer's own platform list;
  - `c_case_sta IN ('OPEN','CLOSED')`;
  - `c_action_sta IN ('OPEN','ERROR','CLOSED')` (default, `actionable` not set).
- 60-day window = `BETWEEN current_date - 60 AND current_date`. That is 61 days inclusive, which is the widest window COMP-27 accepts. `current_date` uses the session time zone, so record it in the results.
- Percentiles use `percentile_disc`, which returns real observed counts.
- Cost estimates are relative, from query shape and the folder DDL. Table sizes are unknown (`GDP-SEARCH-GAPS.md` G11).

**Session preamble (run once per session, before any query):**
```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '<per query, see each header>';
SET LOCAL lock_timeout = '5s';          -- never wait on someone else's lock
-- … query …
ROLLBACK;                               -- nothing to commit
```
Run each query in its own `BEGIN READ ONLY … ROLLBACK` block so that one timeout does not abort the rest. Prefer a read replica if one exists. Whether the configured URLs point at writer or reader is not determinable (G15).

### 2.2 q0 — Column pre-check (run on BOTH databases)
- **DB:** US and UK. **Question:** do the columns the queries use exist, and with what types?
- **Cost:** light (catalog only). **Timeout:** `SET LOCAL statement_timeout = '10s';`
```sql
SELECT table_schema, table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema IN ('wdp','nap')
  AND table_name IN ('acl','case','action')
  AND column_name IN ('c_consumer_name','c_source_system','c_entity_type','c_entity_value','c_status',
                      'i_case_id','c_acq_platform','c_case_sta','c_level2_entity','c_level4_entity','c_level5_entity',
                      'c_action_sta','d_action_processed','d_action_processed_est','c_migration_sta')
ORDER BY 1,2,3;
```
If `wdp.action.d_action_processed_est` is missing, skip the `_EST` variants of c and e. If `nap.action.d_action_processed` is a timestamp rather than a date, note it: `BETWEEN date AND date` on a timestamp drops rows after midnight on the end date, and the app has the same behaviour.

---

### 2.3 US database (schema `wdp`)

#### a — ACL rows per consumer, by source system and entity type
- **DB:** US. **Question:** how many entity values does a consumer send per type, which drives the size of the IN list, and how many consumers are there?
- **Cost:** light (`wdp.acl` only). **Timeout:** `SET LOCAL statement_timeout = '30s';`
```sql
-- a1: per (source system, entity type): distribution of rows per consumer
WITH per AS (
  SELECT c_consumer_name, c_source_system, c_entity_type, count(*) AS n
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
  GROUP BY 1, 2, 3
)
SELECT c_source_system, c_entity_type,
       count(*)                                          AS n_consumers,
       min(n)                                            AS min_rows,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY n)   AS p50_rows,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY n)   AS p90_rows,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n)   AS p99_rows,
       max(n)                                            AS max_rows,
       sum(n)                                            AS total_rows
FROM per
GROUP BY 1, 2
ORDER BY 1, 2;

-- a2: per consumer, rows kept by each region filter (ACLUtils rules), plus overall consumer count
WITH per AS (
  SELECT c_consumer_name,
         count(*) AS n_all,
         count(*) FILTER (WHERE upper(c_source_system) IN ('CORE','VAP','PIN')
                            AND c_entity_type IN ('Chain','OrgID','SuperChain'))           AS n_us_kept,
         count(*) FILTER (WHERE upper(c_source_system) IN ('NAP','LATAM')
                            AND c_entity_type IN ('Company ID','Group ID','Outlet ID'))    AS n_uk_kept,
         count(*) - count(DISTINCT (c_source_system, c_entity_type, c_entity_value))       AS n_duplicate_rows
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
  GROUP BY 1
)
SELECT m.metric,
       count(*)                                            AS n_consumers_total,
       count(*) FILTER (WHERE m.n > 0)                     AS n_consumers_nonzero,
       min(m.n)                                            AS min_n,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY m.n)   AS p50_n,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY m.n)   AS p90_n,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY m.n)   AS p99_n,
       max(m.n)                                            AS max_n,
       sum(m.n)                                            AS total_n
FROM per
CROSS JOIN LATERAL (VALUES ('all_active_rows', n_all), ('us_kept_rows', n_us_kept),
                           ('uk_kept_rows', n_uk_kept), ('duplicate_rows', n_duplicate_rows)) AS m(metric, n)
GROUP BY m.metric
ORDER BY m.metric;

-- a3: rows dropped only because of case (COMP-21 compares entity type case-sensitively;
--     source-system case affects whether PIN is added to the platform list)
SELECT
  count(*) FILTER (WHERE c_entity_type NOT IN ('Chain','OrgID','SuperChain','Company ID','Group ID','Outlet ID')
                     AND lower(c_entity_type) IN ('chain','orgid','superchain','company id','group id','outlet id'))
                                                               AS rows_entity_type_case_mismatch,
  count(*) FILTER (WHERE upper(c_source_system) = 'CORE' AND c_source_system <> 'CORE')
                                                               AS rows_core_not_uppercase,
  count(DISTINCT c_entity_type)                                AS distinct_entity_types,
  count(DISTINCT c_source_system)                              AS distinct_source_systems
FROM wdp.acl
WHERE c_status = 'ACTIVE';
```
a1 outputs source-system and entity-type labels; these are category codes, not entity values.

#### b — Cases per entity value, per mapped US level column
- **DB:** US. **Question:** how many cases sit behind one entity value?
  - Reported for every value in the column, and separately for values that some ACTIVE ACL row references.
  - ACL values with zero cases are included as 0.
- **Cost:** medium. It is one sequential scan of `wdp.case` (no level4/5 index in the folder DDL), plus a hash join to the ACL values. **Timeout:** `SET LOCAL statement_timeout = '10min';`
```sql
WITH acl_vals AS (            -- US-kept ACL values, mapped to the case column COMP-27 uses
  SELECT DISTINCT
         CASE WHEN c_entity_type IN ('Chain','OrgID') THEN 'C_LEVEL4_ENTITY' ELSE 'C_LEVEL5_ENTITY' END AS level_col,
         c_entity_value AS v
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('CORE','VAP','PIN')
    AND c_entity_type IN ('Chain','OrgID','SuperChain')
),
base AS (
  SELECT c.c_level4_entity, c.c_level5_entity
  FROM wdp."case" c
  WHERE c.c_acq_platform IN ('CORE','PIN')
    AND c.c_case_sta IN ('OPEN','CLOSED')
),
per_value AS (
  SELECT 'C_LEVEL4_ENTITY'::text AS level_col, c_level4_entity AS v, count(*) AS n
  FROM base WHERE c_level4_entity IS NOT NULL GROUP BY 2
  UNION ALL
  SELECT 'C_LEVEL5_ENTITY', c_level5_entity, count(*)
  FROM base WHERE c_level5_entity IS NOT NULL GROUP BY 2
),
joined AS (
  SELECT coalesce(p.level_col, a.level_col)                         AS level_col,
         CASE WHEN a.v IS NULL THEN 'not_in_acl' ELSE 'in_acl' END  AS acl_scope,
         coalesce(p.n, 0)                                           AS n
  FROM per_value p
  FULL JOIN acl_vals a ON a.level_col = p.level_col AND a.v = p.v
)
SELECT level_col, acl_scope,
       count(*)                                          AS n_values,
       count(*) FILTER (WHERE n = 0)                     AS n_values_zero_cases,
       min(n)                                            AS min_cases,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY n)   AS p50_cases,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY n)   AS p90_cases,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n)   AS p99_cases,
       max(n)                                            AS max_cases,
       sum(n)                                            AS total_cases
FROM joined
GROUP BY 1, 2
ORDER BY 1, 2;
```

#### c — Actions in the last 60 days per entity value
- **DB:** US. **Question:** how many activity rows, at the COMP-27 count grain of (case, action), does one entity value produce in a maximum-width window?
- **Cost:** medium–heavy.
  - The plain variant can use the `d_action_processed` btree for the action side.
  - The `_EST` variant has no index on its date column in the folder DDL, so expect a full scan of `wdp.action`.
  - Both join to a sequential scan of `wdp.case`.
- **Timeout:** `SET LOCAL statement_timeout = '15min';`
- **Variants:** run **c-EST** (current code) and **c-PLAIN** (`d_action_processed`), because of the ambiguity in §1.3. The only difference is the one marked line.
```sql
WITH acl_vals AS (
  SELECT DISTINCT
         CASE WHEN c_entity_type IN ('Chain','OrgID') THEN 'C_LEVEL4_ENTITY' ELSE 'C_LEVEL5_ENTITY' END AS level_col,
         c_entity_value AS v
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('CORE','VAP','PIN')
    AND c_entity_type IN ('Chain','OrgID','SuperChain')
),
base AS (
  SELECT c.c_level4_entity, c.c_level5_entity
  FROM wdp.action a
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  WHERE c.c_acq_platform IN ('CORE','PIN')
    AND c.c_case_sta IN ('OPEN','CLOSED')
    AND a.c_action_sta IN ('OPEN','ERROR','CLOSED')
    AND a.d_action_processed_est BETWEEN current_date - 60 AND current_date   -- c-EST
 -- AND a.d_action_processed     BETWEEN current_date - 60 AND current_date   -- c-PLAIN (use instead)
),
per_value AS (
  SELECT 'C_LEVEL4_ENTITY'::text AS level_col, c_level4_entity AS v, count(*) AS n
  FROM base WHERE c_level4_entity IS NOT NULL GROUP BY 2
  UNION ALL
  SELECT 'C_LEVEL5_ENTITY', c_level5_entity, count(*)
  FROM base WHERE c_level5_entity IS NOT NULL GROUP BY 2
),
joined AS (
  SELECT coalesce(p.level_col, a.level_col)                         AS level_col,
         CASE WHEN a.v IS NULL THEN 'not_in_acl' ELSE 'in_acl' END  AS acl_scope,
         coalesce(p.n, 0)                                           AS n
  FROM per_value p
  FULL JOIN acl_vals a ON a.level_col = p.level_col AND a.v = p.v
)
SELECT level_col, acl_scope,
       count(*)                                          AS n_values,
       count(*) FILTER (WHERE n = 0)                     AS n_values_zero_actions,
       min(n)                                            AS min_actions_60d,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY n)   AS p50_actions_60d,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY n)   AS p90_actions_60d,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n)   AS p99_actions_60d,
       max(n)                                            AS max_actions_60d,
       sum(n)                                            AS total_actions_60d
FROM joined
GROUP BY 1, 2
ORDER BY 1, 2;
```

#### d — Per-consumer scope size (de-duplicated)
- **DB:** US. **Question:** for each consumer, how many distinct cases and how many 60-day action rows does its full US ACL scope cover?
  - The query reproduces the OR-across-columns match and the consumer's own platform list, with PIN added when the raw value is `CORE`.
  - It reports by the routing class `/cases/activities` would apply (§1.1):
    - `COMP27`: has Chain/SuperChain, no OrgID;
    - `EMPTY_200`: has OrgID plus Chain/SuperChain;
    - `DS`: OrgID only.
  - `ALL` covers every consumer, which is the `/us/cases/activities` view.
- **Added metric** (beyond the brief, same filters): `actions_all_time`, the total action rows on the scope's cases regardless of date. This is what a scope-first plan would touch before applying the date filter.
- **Cost:** heavy. It does per-consumer joins over `wdp.case` (no level4/5 index), `UNION` de-duplication, and two passes over `wdp.action`. **Timeout:** `SET LOCAL statement_timeout = '30min';`. Run off-peak.
- **Variants:** as written it uses `d_action_processed_est`. For **d-PLAIN**, swap the one marked line.
```sql
WITH acl_us AS (
  SELECT DISTINCT c_consumer_name, c_source_system, c_entity_type, c_entity_value
  FROM wdp.acl
  WHERE c_status = 'ACTIVE'
    AND upper(c_source_system) IN ('CORE','VAP','PIN')
    AND c_entity_type IN ('Chain','OrgID','SuperChain')
),
consumer AS (
  SELECT c_consumer_name,
         CASE WHEN bool_or(c_entity_type = 'OrgID') AND bool_or(c_entity_type <> 'OrgID') THEN 'EMPTY_200'
              WHEN bool_and(c_entity_type = 'OrgID')                                      THEN 'DS'
              ELSE 'COMP27' END                                                           AS route_cases_activities,
         array_agg(DISTINCT c_source_system::text)
           || CASE WHEN bool_or(c_source_system = 'CORE') THEN ARRAY['PIN'] ELSE ARRAY[]::text[] END
                                                                                          AS platforms
  FROM acl_us
  GROUP BY 1
),
consumer_cases AS (            -- UNION removes cases matched by more than one value / column
  SELECT u.c_consumer_name, c.i_case_id
  FROM acl_us u
  JOIN consumer k USING (c_consumer_name)
  JOIN wdp."case" c ON c.c_level4_entity = u.c_entity_value
  WHERE u.c_entity_type IN ('Chain','OrgID')
    AND c.c_acq_platform = ANY (k.platforms)
    AND c.c_case_sta IN ('OPEN','CLOSED')
  UNION
  SELECT u.c_consumer_name, c.i_case_id
  FROM acl_us u
  JOIN consumer k USING (c_consumer_name)
  JOIN wdp."case" c ON c.c_level5_entity = u.c_entity_value
  WHERE u.c_entity_type = 'SuperChain'
    AND c.c_acq_platform = ANY (k.platforms)
    AND c.c_case_sta IN ('OPEN','CLOSED')
),
case_counts AS (
  SELECT c_consumer_name, count(*) AS n_cases FROM consumer_cases GROUP BY 1
),
action_counts AS (
  SELECT cc.c_consumer_name,
         count(*) FILTER (WHERE a.d_action_processed_est BETWEEN current_date - 60 AND current_date) AS n_actions_60d  -- d-EST
      -- count(*) FILTER (WHERE a.d_action_processed     BETWEEN current_date - 60 AND current_date) AS n_actions_60d  -- d-PLAIN
         , count(*)                                                                                   AS n_actions_all
  FROM consumer_cases cc
  JOIN wdp.action a ON a.i_case_id = cc.i_case_id
  WHERE a.c_action_sta IN ('OPEN','ERROR','CLOSED')
  GROUP BY 1
),
per_consumer AS (
  SELECT k.route_cases_activities,
         coalesce(cc.n_cases, 0)        AS n_cases,
         coalesce(ac.n_actions_60d, 0)  AS n_actions_60d,
         coalesce(ac.n_actions_all, 0)  AS n_actions_all
  FROM consumer k
  LEFT JOIN case_counts   cc USING (c_consumer_name)
  LEFT JOIN action_counts ac USING (c_consumer_name)
)
SELECT coalesce(route_cases_activities, 'ALL')            AS route_class,
       m.metric,
       count(*)                                            AS n_consumers,
       count(*) FILTER (WHERE m.n = 0)                     AS n_consumers_zero,
       min(m.n)                                            AS min_n,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY m.n)   AS p50_n,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY m.n)   AS p90_n,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY m.n)   AS p99_n,
       max(m.n)                                            AS max_n,
       sum(m.n)                                            AS total_n
FROM per_consumer
CROSS JOIN LATERAL (VALUES ('cases', n_cases), ('actions_60d', n_actions_60d),
                           ('actions_all_time', n_actions_all)) AS m(metric, n)
GROUP BY GROUPING SETS ((route_cases_activities, m.metric), (m.metric))
ORDER BY 1, 2;
```
`EMPTY_200` and `DS` consumers never reach COMP-27 through `/cases/activities`. Their rows show the scope they would have through `/us/cases/activities`.

#### e — Actions per day overall, last 60 days
- **DB:** US. **Question:** what is the date-first baseline, i.e. how many activity rows fall on one day across the platform? It includes days with zero actions.
  - `n_filtered` applies the static filters.
  - `n_unfiltered` counts every action on that date, to show how much the filters remove.
- **Cost:** medium.
  - The plain variant can range-scan `d_action_processed`.
  - The `_EST` variant scans `wdp.action` in full (no index in the folder DDL).
  - Both join `wdp.case` by PK.
- **Timeout:** `SET LOCAL statement_timeout = '10min';`
- **Variants:** **e-EST** / **e-PLAIN**. Swap both marked lines together.
```sql
WITH days AS (
  SELECT d::date AS d
  FROM generate_series(current_date - 60, current_date, interval '1 day') AS g(d)
),
per_day AS (
  SELECT a.d_action_processed_est AS d,                                                -- e-EST
      -- a.d_action_processed     AS d,                                                -- e-PLAIN
         count(*) FILTER (WHERE c.c_acq_platform IN ('CORE','PIN')
                            AND c.c_case_sta IN ('OPEN','CLOSED')
                            AND a.c_action_sta IN ('OPEN','ERROR','CLOSED'))  AS n_filtered,
         count(*)                                                             AS n_unfiltered
  FROM wdp.action a
  JOIN wdp."case" c ON c.i_case_id = a.i_case_id
  WHERE a.d_action_processed_est BETWEEN current_date - 60 AND current_date            -- e-EST
     -- a.d_action_processed     BETWEEN current_date - 60 AND current_date            -- e-PLAIN
  GROUP BY 1
)
SELECT m.metric,
       count(*)                                            AS n_days,
       count(*) FILTER (WHERE m.n = 0)                     AS n_zero_days,
       min(m.n)                                            AS min_per_day,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY m.n)   AS p50_per_day,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY m.n)   AS p90_per_day,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY m.n)   AS p99_per_day,
       max(m.n)                                            AS max_per_day,
       sum(m.n)                                            AS total_61d
FROM days
LEFT JOIN per_day p USING (d)
CROSS JOIN LATERAL (VALUES ('filtered', coalesce(p.n_filtered, 0)),
                           ('unfiltered', coalesce(p.n_unfiltered, 0))) AS m(metric, n)
GROUP BY m.metric
ORDER BY m.metric;
```

---

### 2.4 UK database (schema `nap`; no ACL access)

The column names below come from the COMP-27 SQL (§1.3). There is no `nap` DDL in the workspace, so run q0 first.

Platform `('NAP','LATAM')` is the partner list and the widest ACL list (§1.1–1.2). An ACL consumer with only NAP rows sends `('NAP')`.

Partner scope (no entity filter, `C_PRODUCT_TYPE` only) is not sized here, on either database. It equals query e/h filtered by product type, if wanted.

#### f — Cases per entity value, per mapped UK level column
- **DB:** UK. **Question:** how many cases sit behind one Company / Group / Outlet value? This covers all values in each column, because there is no ACL in this database.
- **Cost:** medium (one sequential scan of `nap.case`; `nap` index coverage not determinable). **Timeout:** `SET LOCAL statement_timeout = '10min';`
```sql
WITH base AS (
  SELECT c.c_level2_entity, c.c_level4_entity, c.c_level5_entity
  FROM nap."case" c
  WHERE c.c_acq_platform IN ('NAP','LATAM')
    AND c.c_case_sta IN ('OPEN','CLOSED')
),
per_value AS (
  SELECT 'C_LEVEL4_ENTITY (CO)'::text AS level_col, count(*) AS n
  FROM base WHERE c_level4_entity IS NOT NULL GROUP BY c_level4_entity
  UNION ALL
  SELECT 'C_LEVEL5_ENTITY (GR)', count(*)
  FROM base WHERE c_level5_entity IS NOT NULL GROUP BY c_level5_entity
  UNION ALL
  SELECT 'C_LEVEL2_ENTITY (OL)', count(*)
  FROM base WHERE c_level2_entity IS NOT NULL GROUP BY c_level2_entity
)
SELECT level_col,
       count(*)                                          AS n_values,
       min(n)                                            AS min_cases,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY n)   AS p50_cases,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY n)   AS p90_cases,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n)   AS p99_cases,
       max(n)                                            AS max_cases,
       sum(n)                                            AS total_cases
FROM per_value
GROUP BY 1
ORDER BY 1;
```

#### g — Actions in the last 60 days per entity value
- **DB:** UK. **Question:** how many activity rows, at the (case, action) grain, does one value produce in a maximum-width window? The UK date column is `D_ACTION_PROCESSED` (§1.3).
- **Cost:** medium–heavy (60-day action slice joined to `nap.case`). **Timeout:** `SET LOCAL statement_timeout = '15min';`
```sql
WITH base AS (
  SELECT c.c_level2_entity, c.c_level4_entity, c.c_level5_entity
  FROM nap."action" a
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  WHERE c.c_acq_platform IN ('NAP','LATAM')
    AND c.c_case_sta IN ('OPEN','CLOSED')
    AND a.c_action_sta IN ('OPEN','ERROR','CLOSED')
    AND a.d_action_processed BETWEEN current_date - 60 AND current_date
),
per_value AS (
  SELECT 'C_LEVEL4_ENTITY (CO)'::text AS level_col, count(*) AS n
  FROM base WHERE c_level4_entity IS NOT NULL GROUP BY c_level4_entity
  UNION ALL
  SELECT 'C_LEVEL5_ENTITY (GR)', count(*)
  FROM base WHERE c_level5_entity IS NOT NULL GROUP BY c_level5_entity
  UNION ALL
  SELECT 'C_LEVEL2_ENTITY (OL)', count(*)
  FROM base WHERE c_level2_entity IS NOT NULL GROUP BY c_level2_entity
)
SELECT level_col,
       count(*)                                          AS n_values_with_actions,
       min(n)                                            AS min_actions_60d,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY n)   AS p50_actions_60d,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY n)   AS p90_actions_60d,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n)   AS p99_actions_60d,
       max(n)                                            AS max_actions_60d,
       sum(n)                                            AS total_actions_60d
FROM per_value
GROUP BY 1
ORDER BY 1;
```
Values with zero actions in the window do not appear here. The number of values with cases comes from f (`n_values`).

#### h — Actions per day overall, last 60 days
- **DB:** UK. **Question:** what is the UK date-first baseline per day, including zero days, filtered vs unfiltered?
- **Cost:** medium. **Timeout:** `SET LOCAL statement_timeout = '10min';`
```sql
WITH days AS (
  SELECT d::date AS d
  FROM generate_series(current_date - 60, current_date, interval '1 day') AS g(d)
),
per_day AS (
  SELECT a.d_action_processed::date AS d,
         count(*) FILTER (WHERE c.c_acq_platform IN ('NAP','LATAM')
                            AND c.c_case_sta IN ('OPEN','CLOSED')
                            AND a.c_action_sta IN ('OPEN','ERROR','CLOSED'))  AS n_filtered,
         count(*)                                                             AS n_unfiltered
  FROM nap."action" a
  JOIN nap."case" c ON c.i_case_id = a.i_case_id
  WHERE a.d_action_processed BETWEEN current_date - 60 AND current_date
  GROUP BY 1
)
SELECT m.metric,
       count(*)                                            AS n_days,
       count(*) FILTER (WHERE m.n = 0)                     AS n_zero_days,
       min(m.n)                                            AS min_per_day,
       percentile_disc(0.5)  WITHIN GROUP (ORDER BY m.n)   AS p50_per_day,
       percentile_disc(0.9)  WITHIN GROUP (ORDER BY m.n)   AS p90_per_day,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY m.n)   AS p99_per_day,
       max(m.n)                                            AS max_per_day,
       sum(m.n)                                            AS total_61d
FROM days
LEFT JOIN per_day p USING (d)
CROSS JOIN LATERAL (VALUES ('filtered', coalesce(p.n_filtered, 0)),
                           ('unfiltered', coalesce(p.n_unfiltered, 0))) AS m(metric, n)
GROUP BY m.metric
ORDER BY m.metric;
```

---

## 3. Run order

**Run plain `EXPLAIN` (no `ANALYZE`) on every query first.** `EXPLAIN` plans the query without executing it. Then:
- If the estimated total cost or row count is far above what the header suggests, stop and send back the plan instead of running.
- If a plan shows a sequential scan on a very large table that is not expected from the header, stop and send back the plan instead of running.
- `EXPLAIN ANALYZE` executes the query, so do not use it as a pre-check.

| Step | Query | DB | Cost | Timeout |
|---|---|---|---|---|
| 1 | q0 column pre-check | US, UK | light | 10s |
| 2 | a1, a2, a3 | US | light | 30s |
| 3 | e-PLAIN | US | medium | 10min |
| 4 | e-EST (skip if q0 shows column missing) | US | medium | 10min |
| 5 | h | UK | medium | 10min |
| 6 | b | US | medium | 10min |
| 7 | f | UK | medium | 10min |
| 8 | c-PLAIN | US | medium–heavy | 15min |
| 9 | c-EST (skip if column missing) | US | medium–heavy | 15min |
| 10 | g | UK | medium–heavy | 15min |
| 11 | d-PLAIN | US | heavy | 30min, off-peak |
| 12 | d-EST (skip if column missing) | US | heavy | 30min, off-peak |

If a query times out, record "timeout at N" in the template. Do not raise the limit without agreement.

---

## 4. Results template (DBA to fill in and return)

Record for the whole run:

| Field | Value |
|---|---|
| Environment / database host label (no credentials) | |
| Read replica or writer | |
| Run date and `current_date` value | |
| Session `TimeZone` (`SHOW TimeZone;`) | |
| PostgreSQL version (`SELECT version();`) | |

**q0 — columns** (one row per column found; list any expected column that is missing)

| schema | table | column | data_type |
|---|---|---|---|
| | | | |

**a1 — ACL rows per consumer by source system × entity type**

| c_source_system | c_entity_type | n_consumers | min | p50 | p90 | p99 | max | total_rows |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

**a2 — rows per consumer by region filter**

| metric | n_consumers_total | n_consumers_nonzero | min | p50 | p90 | p99 | max | total |
|---|---|---|---|---|---|---|---|---|
| all_active_rows | | | | | | | | |
| us_kept_rows | | | | | | | | |
| uk_kept_rows | | | | | | | | |
| duplicate_rows | | | | | | | | |

**a3 — case-sensitivity check**

| rows_entity_type_case_mismatch | rows_core_not_uppercase | distinct_entity_types | distinct_source_systems |
|---|---|---|---|
| | | | |

**b — US cases per entity value**

| level_col | acl_scope | n_values | n_values_zero_cases | min | p50 | p90 | p99 | max | total_cases |
|---|---|---|---|---|---|---|---|---|---|
| C_LEVEL4_ENTITY | in_acl | | | | | | | | |
| C_LEVEL4_ENTITY | not_in_acl | | | | | | | | |
| C_LEVEL5_ENTITY | in_acl | | | | | | | | |
| C_LEVEL5_ENTITY | not_in_acl | | | | | | | | |

**c — US actions (60d) per entity value** (fill once per variant)

| variant | level_col | acl_scope | n_values | n_values_zero_actions | min | p50 | p90 | p99 | max | total_actions_60d |
|---|---|---|---|---|---|---|---|---|---|---|
| PLAIN | C_LEVEL4_ENTITY | in_acl | | | | | | | | |
| PLAIN | C_LEVEL4_ENTITY | not_in_acl | | | | | | | | |
| PLAIN | C_LEVEL5_ENTITY | in_acl | | | | | | | | |
| PLAIN | C_LEVEL5_ENTITY | not_in_acl | | | | | | | | |
| EST | C_LEVEL4_ENTITY | in_acl | | | | | | | | |
| EST | C_LEVEL4_ENTITY | not_in_acl | | | | | | | | |
| EST | C_LEVEL5_ENTITY | in_acl | | | | | | | | |
| EST | C_LEVEL5_ENTITY | not_in_acl | | | | | | | | |

**d — US per-consumer scope** (fill once per variant; `cases` and `actions_all_time` are the same in both variants)

| variant | route_class | metric | n_consumers | n_consumers_zero | min | p50 | p90 | p99 | max | total |
|---|---|---|---|---|---|---|---|---|---|---|
| PLAIN | COMP27 | cases | | | | | | | | |
| PLAIN | COMP27 | actions_60d | | | | | | | | |
| PLAIN | COMP27 | actions_all_time | | | | | | | | |
| PLAIN | EMPTY_200 | cases / actions_60d / actions_all_time | | | | | | | | |
| PLAIN | DS | cases / actions_60d / actions_all_time | | | | | | | | |
| PLAIN | ALL | cases / actions_60d / actions_all_time | | | | | | | | |
| EST | COMP27 | actions_60d | | | | | | | | |
| EST | ALL | actions_60d | | | | | | | | |

**e — US actions per day (61-day window)**

| variant | metric | n_days | n_zero_days | min | p50 | p90 | p99 | max | total_61d |
|---|---|---|---|---|---|---|---|---|---|
| PLAIN | filtered | | | | | | | | |
| PLAIN | unfiltered | | | | | | | | |
| EST | filtered | | | | | | | | |
| EST | unfiltered | | | | | | | | |

**f — UK cases per entity value**

| level_col | n_values | min | p50 | p90 | p99 | max | total_cases |
|---|---|---|---|---|---|---|---|
| C_LEVEL4_ENTITY (CO) | | | | | | | |
| C_LEVEL5_ENTITY (GR) | | | | | | | |
| C_LEVEL2_ENTITY (OL) | | | | | | | |

**g — UK actions (60d) per entity value**

| level_col | n_values_with_actions | min | p50 | p90 | p99 | max | total_actions_60d |
|---|---|---|---|---|---|---|---|
| C_LEVEL4_ENTITY (CO) | | | | | | | |
| C_LEVEL5_ENTITY (GR) | | | | | | | |
| C_LEVEL2_ENTITY (OL) | | | | | | | |

**h — UK actions per day (61-day window)**

| metric | n_days | n_zero_days | min | p50 | p90 | p99 | max | total_61d |
|---|---|---|---|---|---|---|---|---|
| filtered | | | | | | | | |
| unfiltered | | | | | | | | |

**Plans and timings** (one row per query; attach the plain `EXPLAIN` text separately)

| query | EXPLAIN top-node total cost | EXPLAIN top-node rows | ran? (Y / skipped / timeout) | elapsed |
|---|---|---|---|---|
| | | | | |

---

## 5. Optional — offline combination for UK per-consumer scope size

**Use this only if the per-value distributions from f and g are not enough.** There is no cross-database join between the UK `nap` database and the US `wdp.acl`, so a UK per-consumer figure (the UK equivalent of query d) has to be combined outside the databases.

> **Data-handling warning.** This method **exports real entity values** (Company / Group / Outlet IDs) and per-consumer value lists. Both files, and the combined result, must stay inside the secure workspace approved for production data. Never put them in this repository, a ticket, chat, email or any shared drive outside that workspace. Before export, replace consumer names with an opaque key, for example a row number or a salted hash held only in the secure workspace. Delete the exports when the combination is finished. Only the aggregated distribution, in the same shape as the query d template, may leave the secure workspace.

Method (described only, not automated):
1. **UK export (UK database).** For each of `C_LEVEL4_ENTITY`, `C_LEVEL5_ENTITY` and `C_LEVEL2_ENTITY`, export `(level_col, entity_value, i_case_id)` for cases that pass the f filters. Export case ids, not per-value counts, so that cases matched by several values or columns can be de-duplicated per consumer. Separately export `(i_case_id, n_actions_60d, n_actions_all)` using the g filters.
2. **ACL export (US database).** Export `(consumer_key, c_source_system, level_col, entity_value)` from `wdp.acl` with:
   - `c_status = 'ACTIVE'`;
   - `upper(c_source_system) IN ('NAP','LATAM')`;
   - `c_entity_type IN ('Company ID','Group ID','Outlet ID')`, mapped CO→`C_LEVEL4_ENTITY`, GR→`C_LEVEL5_ENTITY`, OL→`C_LEVEL2_ENTITY` (§1.2).
3. **Combine** inside the secure workspace:
   - join step 2 to step 1 on `(level_col, entity_value)`;
   - take distinct `i_case_id` per `consumer_key`;
   - apply the consumer's platform list (its distinct raw `c_source_system` values) against the case's `c_acq_platform`, which must be in the step 1 export;
   - sum the action counts per consumer.
4. **Return** only the distribution: n_consumers, n_zero, min, p50, p90, p99, max and total for cases, actions_60d and actions_all_time.

---

*Facts in §1 are cited to the snapshot; anything the source cannot settle is marked "not determinable from source". No index or query-rewrite proposals are made. No secrets, credentials, entity values or PAN appear in this file.*

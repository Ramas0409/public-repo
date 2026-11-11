# Auto-contest database: release pack for UAT, then prod

Built 2026-11-10 from the POC (`main` at `e9b4cd72`, register seed = office DB step 39). It creates the auto-contest
tables **empty**, loads the **register** (the tables that decide a case and build its questionnaire), and proves the
result matches dev. No case, run, fact, document or user data moves between environments: each environment fills its
own. `wdp."case"` and `wdp.action` are the estate's and are not touched.

Proved on a scratch copy of the POC database: steps 1 to 5a ran with no error, and both fingerprints of the new
database equalled the POC's line for line.

Every file runs in the pgAdmin Query Tool: **File → Open**, then **Execute (F5)**. pgAdmin shows only the last result
of a file; that is the one to compare.

## The files

| File | Run where | What it does |
|---|---|---|
| `structure-fingerprint.sql` | dev, UAT, prod | one row per table: hashes of its columns, constraints, indexes (read-only) |
| `register-fingerprint.sql` | dev, UAT, prod | one row per reason code: hashes of every register part (read-only) |
| `01-wdp-ai-tables.sql` | UAT, prod | schema `wdp_ai` and its 19 tables, no rows |
| `01b-wdp-ai-response-events.sql` | UAT, prod | `wdp.ai_response_events`, the scheduler's queue (skip if the estate provides it) |
| `02-register-seed.sql` | UAT, prod | the register: every reason code's rows, forms, fields, vocabularies (= step 39) |
| `03-enable-codes.sql` | UAT, prod | which codes run here (VISA 13.1 alone, edit per plan) |
| `04-intake-tracker.sql` | UAT, prod | the intake's one tracking row |
| `05a-grants-from-dev.sql` | **dev** | writes out dev's grants as GRANT statements to replay |
| `05b-grants-template.sql` | UAT, prod | the fallback grants, from the code, with user placeholders |

## Step by step

**0. In dev, before anything (proves dev = the POC, so this pack is dev's structure).**
Run `structure-fingerprint.sql` and `register-fingerprint.sql` in dev and compare with the expected rows below.
- Both equal: go on.
- A structure row differs: run the detail query at the bottom of `structure-fingerprint.sql` for that table in dev and
  send it; the difference is decided (dev fixed, or the pack changed) before UAT.
- A register row differs: stop; dev is not on step 39.

Run `05a-grants-from-dev.sql` in dev and save its result: the grants to replay in step 5.

**1. In UAT, as the role that is to own `wdp_ai`:** run `01-wdp-ai-tables.sql`, then `01b-wdp-ai-response-events.sql`
(as the owner of the `wdp` tables; skip it if UAT's estate already has the table).

**2.** Run `02-register-seed.sql`.

**3.** Edit the `IN` list of `03-enable-codes.sql` to UAT's plan, run it, check the list it prints.

**4.** Run `04-intake-tracker.sql`: one row, `AI-INTAKE-00`, `TRACKER`. Keep the table otherwise empty until a run is
wanted.

**5. Grants.** Take the result of step 0's `05a`, change each dev user name to UAT's, run it in UAT. Where dev's grants
could not be read, fill the placeholders of `05b-grants-template.sql` and run that instead. The UK database's `nap`
grant (office DB step 36) is at the end of `05b`.

**6. Prove UAT = dev.** Run both fingerprints in UAT. Every row must equal the expected rows below (and dev's). Any
difference is a stop: trace it before deploying anything.

**7. Deploy and switch on.** Deploy the same image tags as dev. Start the event service with
`scheduler.silent-mode-codes` and `review-mode-codes` empty; set the report's bootstrap admin for UAT. Run one 13.1
case end to end, then set `silent-mode-codes: ["13.1"]`.

**Prod:** steps 1 to 7 again with **these same files** (not a new export from dev). In step 6, prod must equal UAT.

**Afterwards:** every later change is one numbered DB step, applied dev → UAT → prod in order, with both fingerprints
run after it in each. A register change moves `register-fingerprint.sql`'s rows; the new expected rows travel with the
step. Keep a list of the steps applied, with dates, per environment: nothing in the database records it.

## Expected: `register-fingerprint.sql`

`reason_code.enabled` is left out (each environment's own choice), so these rows are the same everywhere.

```
 reason_code | reason_code_row | requirements | time_limits | record_rules | fact_keys | q_paths  | q_fields | q_options | documents
-------------+-----------------+--------------+-------------+--------------+-----------+----------+----------+-----------+-----------
 ALL         |                 |              |             |              |           |          |          | 75f2ffe8  |
 VISA 10.4   | f0415f71        | b0139127     | 72c89d43    |              | 56ee1914  | c12a67bf | a01007e9 | 356b352a  | 489cbef0
 VISA 12.5   | e5eccd4e        | 393aac23     | 72c89d43    | d7081dbc     | 2409965c  | 6bd965f4 | d66df609 |           | 0b893ff3
 VISA 13.1   | af610123        | ae913ad0     | e07aa0c4    | fdb930ad     | 83b01927  | d9effba8 | d5f6dc06 |           | 2beb1392
 VISA 13.2   | 23e9f56b        | e03198ed     | 0c759516    |              | 90341161  | afa45540 | 780b8d8f |           | edce32ea
 VISA 13.3   | 2a16cafb        | 70673500     | 02b1867f    | c0af0520     | c413348d  | 6b8886e7 | 8bd76213 |           | e5b84ed4
 VISA 13.7   | c3060fd0        | 34fe9711     | 966122f8    | 604cc8ba     | 2b09a9fa  | 5f3391d7 | c60a288e |           | 608449bc
```

## Expected: `structure-fingerprint.sql`

table | columns | columns_md5 | constraints | constraints_md5 | indexes | indexes_md5

```
wdp.ai_response_events|20|c1a42ebbeb8c5e5dcea0ef3fa453dd23|2|9124ee2498095ebf074d8b1c8a4bfa4e|2|975fa78b9fc0cd114e3e17999539e9ba
wdp_ai.case_document|5|91a4c006232328ebd810da8fb6e4a128|3|c6cbf74978d949f64c8c37f4109cf025|0|-
wdp_ai.classifier_run|16|81866319edfc928da795097761b65830|2|1f6a5d8bea5e7169a76fcb6046b59983|0|-
wdp_ai.contest_comparison|20|7d2fad03d652ce1134c9eb30f14520d5|2|238e82482f2443ae50f14c250ea7d4b4|0|-
wdp_ai.document_text|7|7c94ec72f33037d05536454ca21b6413|4|6e71efa893f19d12618a080a38011ed4|0|-
wdp_ai.document_token_map|3|e9d1fa4a9cc8eceddc324b14fbb488cd|2|8fa950b73e3e2f4b666fb11647cc35da|0|-
wdp_ai.extracted_fact|17|cb9928166eddd5ffcfb018fbb2d37e45|5|77a188f77cfced60023d81e9936c789a|1|e6d5d7c0b292b8e32029ae2c2c2dac15
wdp_ai.fact_key|10|7a285237a0027819ba979f64b88485d1|7|d78b0f5497502b24d424da82920ec542|0|-
wdp_ai.merchant_document_type|7|8cd0a06b06cd9d10f66cd495df26d48d|3|78a458244354a76fca32e888165154fb|0|-
wdp_ai.model_call_usage|21|1a306166113e73ecc5702c2e7593a46e|2|a3db25c3e25f09045395a9fce5c2560e|3|faf2a28a155af26a7781ed258b230c00
wdp_ai.qa_ops_questionnaire|10|37cdabbb96020716c3edc81b9a62e5f0|2|d8ca575c2df79c7810e1e9fdaae1b386|0|-
wdp_ai.questionnaire_field|8|c410a934afa71b29e28e2aaddc9ad579|5|d9838723fc66065b14c27bc992f7bfc8|0|-
wdp_ai.questionnaire_option|7|e3eab301a12da534bea08c028de0ccbb|4|1052e8874f51a6733caaf1bc65d9d267|0|-
wdp_ai.questionnaire_path|9|d0c7c2b3f521bfb75148e3b6ba0e891c|7|a6826d4b5635549b18fd2765a73b9973|0|-
wdp_ai.reason_code|17|2b28b168a119906b86cc3841b715a42c|2|85fdd487b06caf54db57f470c3afdf77|0|-
wdp_ai.reason_code_fact_key|4|94c4afc51376afc19f815ada0012b6e1|5|50551842b9773af4ca76646b5bf09818|0|-
wdp_ai.report_user|10|5c8df7acbf097b2b478c8cfc07fadf8a|4|eae8847bcccd7c88b6454ecd554c332b|0|-
wdp_ai.requirement|18|c3044278a46b87c34f3b90b14942e1bc|10|4900f03a27cfb535a014b546c79ad46b|0|-
wdp_ai.requirement_record_rule|7|0abd1e6f484a0a4d7830dca3b2d3e41a|4|ef7d3a5ac0eaf2d538233c5a36d44229|0|-
wdp_ai.requirement_time_limit|9|62ae910f47b7272b2c5d298bd1936faa|6|0775acbba792c9651a9c14c8226458d4|0|-
```

## Notes

- The tables were exported by the POC's PostgreSQL 17 `pg_dump`, with its two 17-only lines (`\restrict` and
  `transaction_timeout`) taken out, so the scripts run on a 16 server and in pgAdmin.
- `01b` is provisional, like office DB step 30: the stand-in's DDL until the estate writes its own table.
- Every id is an identity column: no sequence grant is needed for an INSERT.
- `prompt_baseline` (dev tooling) and dev's `*_bak_*` and archive tables are not in the pack.

# Auto-Contest API — design decisions

**Living document.** One section per endpoint, added as we work through them. Each section records
the agreed request, the agreed response, the evaluation logic, and a numbered decision log with the
reasoning — so a decision can be revisited later without re-deriving why it was made.

| | |
|---|---|
| Settled so far | `POST /api/v1/contestability`, `GET /api/v1/requirements`, `POST /facts/extract`, `POST /facts/judge`, `POST /documents/classify`, `POST /narrative`, `POST /api/v1/wdp/auto-contest` |
| Last updated | 2026-08-26 |
| Supersedes | `docs/API-CONTESTABILITY-ENDPOINT-SPEC.md` — that document describes the **old** contract and should be deleted once this one is accepted |

### How each endpoint section is structured

Every endpoint gets the same five subsections, in this order. Reviewing an endpoint means filling
these in; nothing is considered settled until all five exist.

| Subsection | What it holds |
|---|---|
| **`.1` Request** | the agreed request object, field by field, with what each field is for |
| **`.2` Response** | the agreed response object, same treatment, plus any invariants |
| **`.3` Logic** | the evaluation steps as ordered bullets — what the endpoint does, in sequence, from receiving the request to building the response. High level: one line per step, no code |
| **`.4` Decision log** | every call made, numbered and prefixed by endpoint (`C1…` for contestability), each with its reasoning. **Reversals are recorded as reversals**, not silently overwritten |
| **`.5` Open items** | what is unresolved, numbered with the same endpoint prefix (`CO1…`, `RO1…`), and what each one needs in order to close |

The **Logic** subsection is the one that drives implementation — it is the step list a developer
works down. Keep it free of field-level detail; `.1` and `.2` carry that.

---

## Part 1 — Decisions that apply to every endpoint

These came out of the `/contestability` discussion but are architectural, not endpoint-specific.
Apply them when we design the rest.

### 1.1 The call sequence

```
1. Orchestrator  →  GET  /api/v1/requirements?network=&reasonCode=
                    returns the fact keys this condition needs

2. Orchestrator     resolves each key — system of record, document extraction, or LLM judgment

3. Orchestrator  →  POST /api/v1/contestability
                    sends a flat list of resolved fact key/value pairs

4. Rules engine     evaluates the signed tree over those facts and returns a decision
```

**The rules engine evaluates. It does not gather.** No outbound calls, no model calls, no document
reads. Same facts in, same decision out, every time.

### 1.2 `/requirements` is a hard prerequisite

The orchestrator must call it before `/contestability`. It caches the answer per
network + reason code (Spring Cache); a new tree release requires an orchestrator restart. Tree
versions change roughly twice a year, so this is cheaper than calling every time.

**Staleness risk:** if the cache is stale the orchestrator resolves the *old* key list and the
engine decides on a partial fact set — which looks like an ordinary review, not a deployment
mistake. Mitigation: `/requirements` returns `treeVersion`; the orchestrator echoes it on the
contestability call and the engine logs a mismatch.

### 1.3 The engine classifies facts; the caller never does

The caller says **what** a fact is and, where relevant, **how well it was read**. It never says what
the fact is *allowed to do*.

| Property | Whose property | Who supplies it |
|---|---|---|
| Provenance class (system of record / document / model judgment) | the **key** — same on every case | **vocabulary.** Engine looks it up |
| Evidence class (what could contradict it) | the **key** | **vocabulary.** Engine looks it up |
| Read quality (clean text vs blurry scan) | the **case** — differs each time | **caller.** Only the extractor knows |

**Ceiling rule: the lowest of the three wins.** The caller can only ever lower a fact's standing,
never raise it. So a bug — or a caller that mislabels an LLM answer as settlement data — can never
promote a judgment into decision-grade evidence.

Only `COMPUTED` and `EXTRACTED_VERIFIED` may decide a ground. `MODEL_JUDGED`, `SELF_ATTESTED` and
`CONFIGURED_ASSUMPTION` may support one and route it to a person; they may never carry a filing.

### 1.4 Nothing files unattended

Auto-submit is removed. Every contestable case goes to operations review, because contesting is a
business decision with financial impact. This retires the whole `mayBeSubmitted()` gate and every
field that existed to keep an unattended filing safe.

### 1.5 Don't echo what the caller already holds

If the orchestrator produced it and still has it in memory, the engine does not send it back.
Each service stamps only what it alone knows — the engine stamps tree version, rulebook edition and
approval; the orchestrator stamps documents, spans and content hashes.

**Consequence:** a stored decision is no longer self-contained. The orchestrator **must** persist its
extraction record alongside the decision, rejoinable by fact key — pre-arbitration months later needs
both halves and the engine no longer holds a copy of the second one.

### 1.6 Absent means absent

A key that is not in the list was not established. The engine leaves the ground undecided and routes
it to a person; it never reads absence as "false". **Never send `false` or `0` to stand in for
"we didn't look."**

### 1.7 One source of truth per fact

Where two fields would say the same thing, keep one. Applied throughout: `rulesFired` deleted
(duplicated `citedGrounds`), `requiredDocumentType` deleted (duplicated `missingDocuments`),
`PART_CONTEST` not added (the amounts already say it).

---

## Part 2 — `POST /api/v1/contestability`

| | |
|---|---|
| Method | `POST /api/v1/contestability` |
| Auth | none today — **open item** |
| Status codes | always `200` on a well-formed body. `NOT_JUDGEABLE` is a decision, not an error |
| Statefulness | stateless, synchronous, no side effects |

### 2.1 Request

```jsonc
{
  "network":           "VISA",         // REQUIRED — selects the rulebook
  "reasonCode":        "13.1",         // REQUIRED — selects the tree
  "acquiringPlatform": "CORE",         // NAP = Europe, CORE = US. Only read where the condition
                                       // has regional variants
  "caseNumber":        "CASE-000123",  // correlation / logging
  "actionSequence":    "1",            // correlation / logging

  "facts": [
    { "key": "transaction.disputedAmount",  "value": "250.00" },
    { "key": "case.currency",               "value": "USD" },
    { "key": "credit.exists",               "value": "false" },
    { "key": "transaction.mcc",             "value": "5964" },
    { "key": "dispute.solelyAboutQuality",  "value": "false" },

    { "key": "delivery.deliveredDate",      "value": "2026-06-05",
      "readQuality": "DIGITAL_TEXT" }
  ]
}
```

**Every fact is `{key, value}`.** One optional field — `readQuality` — on facts read out of a
document, because it is the only thing about a fact the engine cannot derive from its own vocabulary
and the caller cannot fake in its own favour.

#### `readQuality`

Set by `dispute-llm-service` **before the model is called**, from the document itself and the OCR
engine. Never from the LLM's self-reported confidence.

| Value | Set when | May decide a ground? |
|---|---|---|
| `DIGITAL_TEXT` | the page has a real text layer (≥ 40 chars) | yes |
| `VISION_HIGH` | OCR'd, mean line confidence ≥ 0.90 | yes |
| `VISION_LOW` | OCR'd below threshold, or unrenderable | **no — refused** |

A document takes the quality of its **worst** page.

#### The routing header is not evidence

`network`, `reasonCode` and `acquiringPlatform` select which tree runs. They are not fact keys and
are not evaluated. `caseNumber` and `actionSequence` are logged and decide nothing.

### 2.2 Response

```jsonc
{
  "routing": "CONTEST",                // CONTEST | ACCEPT | NOT_JUDGEABLE

  "appliedRule": {
    "reasonCode":      "13.1",
    "region":          "ALL",          // which rule set ran — the engine resolved it
    "treeVersion":     "v6",
    "rulebookEdition": "18 April 2026",
    "approvedBy":      "J. Smith"      // null where no scheme expert has signed the tree
  },

  "currency":        "USD",
  "disputedAmount":  250.00,
  "contestedAmount": 250.00,
  "acceptedAmount":    0.00,

  "missingDocuments": [],

  "citedGrounds": [
    {
      "nodeId": "G5",
      "groundRole": "REPRESENTMENT_GROUND",     // or INVALIDITY_ITEM
      "anchorPassageId": "VISA-13.1-11.10.7.6",
      "requirementText": "The Merchandise was received by the Cardholder or by a person authorised by the Cardholder to take delivery, evidenced by proof of delivery.",
      "facts": [
        { "key": "delivery.deliveredDate",      "value": "2026-06-05" },
        { "key": "delivery.status",             "value": "DELIVERED" },
        { "key": "dispute.daysAfterAgreedDate", "value": "9" }
      ],
      "amount": 250.00,
      "restsOnUnconfirmedReading": false
    }
  ]
}
```

#### `routing`

| Value | Meaning | What ops does |
|---|---|---|
| `CONTEST` | grounds were satisfied | review the argument, approve, file |
| `ACCEPT` | no grounds satisfied | concede — **unless `missingDocuments` is non-empty** |
| `NOT_JUDGEABLE` | no signed tree serves this network + reason code, or the region could not be resolved | back to the existing manual process |

There is no separate `REVIEW` value: a contest is always reviewed, so `CONTEST` already means
"recommended, pending approval". There is no `PART_CONTEST` value either — a partial is
`contestedAmount > 0 && contestedAmount < disputedAmount`.

**`ACCEPT` with a non-empty `missingDocuments` is the remediable case.** The ground could have been
decided and wasn't, because a document is missing on this case. Fetch it, re-run, and the case may
flip to `CONTEST`. Without acting, it is conceded — which is what `ACCEPT` correctly says.

#### `citedGrounds`

The grounds the decision rests on, each with the passage it rests on.

| Field | Purpose |
|---|---|
| `nodeId` | traceability into the signed tree; the filing-reason derivation keys off it |
| `groundRole` | `REPRESENTMENT_GROUND` / `INVALIDITY_ITEM` — **the filed response reason derives from this.** Node ids can't do it; they are not stable across trees |
| `anchorPassageId` | the rulebook citation |
| `requirementText` | **verbatim, containment-checked against the corpus, covered by the SME signature.** What the contest letter argues from |
| `facts` | key **and value**, in predicate order, deduplicated. Includes engine-derived facts the orchestrator never saw |
| `amount` | what this ground carries of the dispute. **`null` = met but unpriceable** |
| `sanctioned` | `false` = met on an interim reading no scheme expert has confirmed. The reviewer must see it |

**Invariants:**
- `Σ citedGrounds[].amount == contestedAmount`
- `contestedAmount + acceptedAmount == disputedAmount`

**`amount: null` is load-bearing.** The ground is satisfied — there is a real argument — but no
number could be produced for it. A non-empty `citedGrounds` with `contestedAmount: 0` is a win
nobody priced, and must not quietly become a concession.

**`facts` carries values, not just keys,** for two reasons: the letter argues from the value
("delivered on 5 June 2026"), and some facts are engine-derived, so the orchestrator has never seen
them. It is also what separates two wins that share a role — `credit.*` argues a refund was
processed, `delivery.*` argues the goods arrived.

### 2.3 Logic

- **Select the tree** — network + reason code; resolve region from `acquiringPlatform` only where the
  condition has regional variants
- **No tree, or region unresolvable** → `NOT_JUDGEABLE`, stop. Never fall back to the general rule
  set: a regional set carries preconditions the general one lacks, and serving the general tree would
  drop them and file as though they never existed
- **Ingest facts** — refuse anything that is not a vocabulary key; refuse `VISION_LOW`
- **Derive** — compute derived keys from the base facts, following the vocabulary's `inputs` graph
- **Apply ceilings** — provenance and evidence class from the vocabulary, read quality from the
  caller; **lowest wins.** Mark each fact may-decide or may-not
- **Evaluate the tree** — walk the nodes; a ground resting on a capped fact is not satisfied
- **Price** — allocate the disputed amount across satisfied grounds; unpriceable ground →
  `amount: null`
- **Route** — grounds satisfied → `CONTEST`; none → `ACCEPT`
- **Build the response** — `citedGrounds` from satisfied nodes, the amounts, `appliedRule`,
  `missingDocuments`

### 2.4 Decision log

| # | Decision | Reasoning |
|---|---|---|
| C1 | Request becomes a flat fact list; `EvidenceBundle` and its 11 nested types are deleted | ~92 fields, ~35 read. The engine no longer gathers, so it no longer needs a shape to gather into |
| C2 | Derivation stays in the engine | Derivations are rulebook *readings* (which MCCs are airline; "a credit" meaning "a credit at least equal to the disputed amount within tolerance"), not plumbing. They carry `operationalisation` notes and SME sanctions. Moving them out moves unsigned rule interpretation outside the signed artifact |
| C3 | The orchestrator resolves **base** keys only; the engine computes derived ones | Follows from C2. Requires wiring the vocabulary's existing-but-unused `inputs` column |
| C4 | The engine derives a key's class from its own vocabulary; the caller never states it | The producer of a fact must never also decide whether the fact may carry a filing |
| C5 | The caller reports `readQuality` only | It is the one property of a fact that is per-case and unknowable to the engine. `VISION_HIGH` decides but historically could not auto-submit; moot under C7 but the band is still recorded |
| C6 | `evidenceClass` moved from request to vocabulary | It answers "what could contradict this?", which is a property of the key and the platform schema, not of the reading. Asking the LLM to classify it would be the model grading its own output |
| C7 | Auto-submit removed; every contestable case is reviewed | Contesting is a business decision with financial impact |
| C8 | `decision` + `disposition` collapsed into one `routing` field | Once nothing auto-files, the two fields carried one bit of difference between them |
| C9 | `contestedAmount` and `proposedContestAmount` merged | Follows from C7 — with nothing filing unattended, every contested amount **is** a proposal. Note this *reverses* an earlier position: the two were kept separate as a safety control, and C7 deleted the path that control protected |
| C10 | `reviewAmount` removed | Meant "satisfied but not filing-ready, so neither contested nor conceded". Nothing is filing-ready without a person now. Met-but-unpriceable lives on the ground as `amount: null` |
| C19 | `sanctioned` renamed `restsOnUnconfirmedReading` | **Corrects an inversion.** In code `isSanctioned() == true` means the key carries an INTERIM sanction — the *risky* case, which is why it caps. This document had `false` as the risky value, so an implementer would have flagged exactly the wrong grounds. The new name carries no `true = good` / `true = bad` ambiguity |
| C11 | `rationale` removed | Derived prose; nothing should ever parse it. Its three load-bearing facts were promoted: `sanctioned` on the ground, `amount: null` for unpriceable, and the split is already in the amounts |
| C12 | `blockedGrounds` removed | The orchestrator called `/requirements` and knows which keys it failed to resolve. `missingDocuments` keeps the actionable roll-up |
| C13 | Reference fields removed from facts (`quote`, `documentRef`, `contentHash`, `page`) | The orchestrator produced them and still holds them for the questionnaire and letter. `page` was never read at all. Cost: the engine permanently gives up span verification — see open item CO4 |
| C14 | `winnability`, `rulesFired`, `factSheet`, `compellingEvidence`, `documentContentHash` removed | Respectively: mechanically derived from the decision; duplicated `citedGrounds`; mostly echo once the request became storable; never populated; no longer produced |
| C15 | `Decision.PENDING_DOC` and `Disposition.OUT_OF_SCOPE` deleted | Neither has a producer. Under the new flow the orchestrator knows about a missing document before it calls |
| C16 | `duplicate`, `history`, `compellingEvidence` groups deleted | Coded-catalogue era. The catalogues were deleted; the groups outlived them. `CompellingEvidenceMatcher` is referenced by nothing |
| C17 | Field renames | `disposition`→`routing`, `decidedUnder`→`appliedRule`, `conditionCode`→`reasonCode`, `groundsOfRecord`→`citedGrounds`, `documentsToObtain`→`missingDocuments`, `treeSignatureStatus`→`approvedBy`, `corroboration`→`evidenceClass` |
| C18 | Corroboration values renamed | `SELF_CONTAINED`→`ON_DOCUMENT_FACE`, `SELF_ATTESTED`→`MERCHANT_CLAIM`. Two of three values shared a `SELF_` prefix and meant opposite things — one may decide a ground, one may never |

### 2.5 Open items

| # | Item | Needs |
|---|---|---|
| CO1 | Does `approvedBy: null` (POC-signed tree) block filing after ops approval? | A decision. Recommendation: the review UI flags it — approving a filing built on a rule no scheme expert reviewed is still filing on an unreviewed rule. **Verify a POC-signed tree leaves `approvedBy` null rather than stamping a placeholder name** |
| CO2 | `evidenceClass` for ~80 vocabulary keys | One-off per-key classification. `source_side` is a hint, not the answer — a carrier record obtained *by* the merchant is still third-party in origin |
| CO3 | `readQuality` is per-page; the fact is per-line | OCR confidence is averaged across the page. 99 clean lines and one garbled line carrying the delivery date reads `VISION_HIGH`. A per-value confidence would be tighter |
| CO4 | Span verification moves wholly to the orchestrator | `EXTRACTED_VERIFIED` means "model-read **and span-verified**". The engine never re-checked the span, so nothing breaks today — but it can never start. The verification must not weaken in the move |
| CO5 | The `0.90` vision threshold is unmeasured | It is the line between "may decide a ground" and "may not". It must be earned by measuring extraction accuracy on the real photographed population, not on synthetic degradation, which is uniform and will overstate it |
| CO6 | No auth on the endpoint | Before this leaves the POC |
| CO7 | `conditionCode` inside the signed tree files | The API renames to `reasonCode`. Renaming it inside the tree artifacts is a separate, larger change — deferred |
| CO8 | A key sourced from two document types | `evidenceClass` is per-key today because `documentType` is single-valued. If a key ever needs a carrier record *or* a merchant spreadsheet, the class becomes per (key × documentType) |
| CO9 | Two supplied facts for the same key are never compared against each other | `addSuppliedFacts` builds its `computed` map **once, before the loop**, from case-record facts only, and never adds an accepted supplied fact back into it. So two proof-of-delivery documents giving different dates both land on the sheet with **no `CONFLICT` raised**, and which one the evaluator reads is an accident of ordering. Arises now that `/facts/extract` may return one fact per (key, document) — see Part 4. Fix: insert each accepted fact into `computed` as it is accepted |

---

## Part 3 — `GET /api/v1/requirements`

| | |
|---|---|
| Method | `GET /api/v1/requirements` |
| Auth | none today — **open item** |
| Status codes | `400` on a missing or blank parameter. Otherwise always `200` — an unserved condition is a routing fact, not an error |
| Statefulness | read-only, model-free, case-independent. It never decides |

### 3.1 Request

```
GET /api/v1/requirements?network=VISA&reasonCode=13.1&acquiringPlatform=CORE
```

| Param | Required | Purpose |
|---|---|---|
| `network` | yes | selects the rulebook |
| `reasonCode` | yes | selects the tree. Sub-conditions resolve to the parent's tree |
| `acquiringPlatform` | yes | `NAP` = Europe, `CORE` = US. The engine maps platform to region |

Missing or blank → `400`. **No fallback to the general tree.**

**No case identifiers, no facts.** The question is *"what would this condition need"*, and the answer
is the same for every dispute under it. The moment this endpoint takes a case it becomes a second
evaluation path, and two paths that can disagree is the failure this estate keeps finding.

#### Why `acquiringPlatform` is required

`/requirements` previously always answered for the **general** tree, while `/contestability` may
select a **regional** one with different fact keys. The orchestrator would resolve the general key
list; the engine would then evaluate Europe's tree, whose extra bonding keys were never resolved.
The case concedes and looks like an ordinary `ACCEPT`.

Harmless as an inefficiency; a silent wrong answer once `/requirements` became a cached hard
prerequisite (§1.2).

> **Invariant: `/requirements` must answer for the same tree `/contestability` would select.**
> Anything that selects a tree in one must select it in the other.

### 3.2 Response

```jsonc
{
  "appliedRule": {
    "reasonCode":       "13.1",
    "conditionName":    "Merchandise Not Received",
    "region":           "ALL",
    "treeVersion":      "v6",
    "rulebookEdition":  "18 April 2026",
    "approvedBy":       "J. Smith",
    "acquirerWorkflow": "DISPUTE_RESPONSE"
  },

  "evaluationFacts": [
    { "key":        "transaction.disputedAmount",
      "type":       "measure",
      "source":     "SYSTEM_OF_RECORD",
      "sourceRef":  "case.a_dispute_orig",
      "sourceSide": null,
      "question":   "What amount did the cardholder dispute?" },

    { "key":        "delivery.deliveredDate",
      "type":       "value",
      "source":     "DOCUMENT",
      "sourceRef":  "proofOfDelivery",
      "sourceSide": "MERCHANT",
      "question":   "On what date was the merchandise delivered?" },

    { "key":        "dispute.solelyAboutQuality",
      "type":       "truth",
      "source":     "MODEL_JUDGMENT",
      "sourceRef":  "disputeDescription",
      "sourceSide": "ISSUER",
      "question":   "Does the dispute concern quality alone?" }
  ]
}
```

**Not served** — no tree for this network + reason code, or the region could not be resolved →
`appliedRule: null`, `evaluationFacts: []`. Mirrors `NOT_JUDGEABLE`.

`appliedRule` is **the same object shape as the contestability response**, so the orchestrator can
compare what it planned against with what actually decided.

#### `evaluationFacts` — one uniform object

Six fields, identical in every lane:

| Field | Meaning |
|---|---|
| `key` | opaque identifier — the orchestrator never interprets it, only echoes it back on `/contestability` |
| `type` | `truth` / `value` / `measure` — how the answer is read. Shapes how `/facts/extract` and `/facts/judge` ask the model |
| `source` | which lane resolves it: `SYSTEM_OF_RECORD` / `DOCUMENT` / `MODEL_JUDGMENT` |
| `sourceRef` | **where to read it** — a case column, a document type, or a case text field |
| `sourceSide` | who holds it: `ISSUER` / `MERCHANT` / `EITHER`, null where the vocabulary has not said |
| `question` | what it asks, in plain words. Present on every entry, whatever the lane |

| `source` | `sourceRef` is | Example |
|---|---|---|
| `SYSTEM_OF_RECORD` | the case column | `case.a_dispute_orig` |
| `DOCUMENT` | the document type to open | `proofOfDelivery` |
| `MODEL_JUDGMENT` | the case text to read | `disputeDescription` |

**Keys are base only** — the transitive closure down the vocabulary's `inputs` graph. Derived keys
never appear; the engine computes them (C2, C3).

### 3.3 Logic

- **Validate params** — any of the three missing or blank → `400`
- **Select the tree** — network + reason code, region from `acquiringPlatform`; identical resolution
  to `/contestability`, no fallback to the general set
- **Region is settled by tree selection** — a regional rule set is its own signed tree, so no node
  from another region is ever in scope
- **No tree, or region unresolvable** → `appliedRule: null`, `evaluationFacts: []`, stop
- **Collect keys** — from the predicates of nodes that can actually decide; skip inert
  non-determinable nodes
- **Include scope keys** — every key read by a node's `appliesWhen`. Without them the scope is
  unreadable, and an unreadable scope leaves the node in play but unable to fire
- **Resolve to base keys** — walk the vocabulary's `inputs` graph transitively; derived keys drop out
- **Drop unobtainable keys** — the caller cannot act on them
- **Annotate each** from the vocabulary — `source`, `sourceRef`, `sourceSide`, `question`
- **Build `appliedRule`** from the tree
- **Sort and deduplicate** so two responses can be diffed

### 3.4 Decision log

| # | Decision | Reasoning |
|---|---|---|
| R1 | `acquiringPlatform` added and **required** | `/requirements` answered for the general tree while `/contestability` may select a regional one. Cached and made a hard prerequisite, that becomes a silent wrong answer on the cases whose rules are strictest |
| R2 | Required always, not conditionally | Conditional logic is where the two endpoints drift apart. The orchestrator always has it on the case row, so demanding it costs nothing |
| R3 | `factKeys`, `documentSourced`, `modelJudged`, `gaps` collapse into one `evaluationFacts` list | They overlapped, so the caller had to cross-reference to learn how to resolve a key. One list, each key annotated with how to get it |
| R4 | One uniform object across all three lanes | `sourceRef` means "where to read it" in every lane, absorbing `documentType` and `readFrom`. One field, one job |
| R5 | `key` stays on every entry | The orchestrator never interprets it, but the engine needs it to look up provenance, evidence class and derivation inputs. Reverse-mapping `sourceRef` to a key breaks the moment two keys read the same column |
| R6 | A base key must name exactly one source field | What makes `sourceRef` an instruction rather than a hint. Anything composite — `authorization.obtained` = `found && approved` — is **derived**, with its columns as inputs. Gives Phase 1 of the implementation a hard test |
| R7 | `question` present on every entry, including system-of-record | It documents what the key means in plain words, whatever the lane resolving it |
| R8 | `served` dropped | `appliedRule: null` already says it (§1.7) |
| R9 | `gaps` dropped | Unobtainable keys — the caller cannot act on them. Same reasoning that deleted `blockedGrounds` (C12) |
| R10 | `unknownKeys` dropped | Tree keys missing from the vocabulary are a startup validation alarm, not a runtime response field |
| R11 | `conditionsServed` dropped | "What does the estate cover" is a different question and belongs to its own endpoint |
| R12 | `derivedKeys` dropped | They are not in `evaluationFacts`, so the orchestrator was never going to look for them |
| R13 | `appliedRule` reuses the contestability response shape | Lets the orchestrator compare what it planned against with what decided — the cache-staleness check in §1.2 |
| R14 | Scope (`appliesWhen`) keys are **included**, not excluded | Counter-intuitive but load-bearing: an unresolved scope key leaves the node in play and unreadable, so the ground can never fire |
| R15 | Endpoint stays case-independent | No case identifiers, no facts. A lookup that took a case would be a second evaluation path |
| R16 | `type` (`truth` / `value` / `measure`) added to each entry | `/facts/extract` and `/facts/judge` need it to shape how the model is asked to answer. The vocabulary already carries it; inferring a data type from prose would be a guess we do not need to make |

### 3.5 Open items

| # | Item | Needs |
|---|---|---|
| RO1 | `describe()` does not collect `appliesWhen` keys today | **Live gap.** A condition with scoped nodes under-reports what it needs. Fix with R14 |
| RO2 | Does `describe()` collect **branch** predicate keys? | Unverified. `DocumentFactSource` walks branch predicates; the requirements walk may not. Check before implementing |
| RO3 | Removing `conditionsServed` leaves nowhere to ask "what does the estate cover" | A separate endpoint, if ops needs it |
| RO4 | Unobtainable keys are dropped from the response | Confirm the contestability response explains adequately why a case conceded on one — the caller now has no forewarning |
| RO5 | No auth on the endpoint | Before this leaves the POC. Same as CO6 |
| RO6 | The orchestrator has no `sourceRef` → location resolver | **`sourceRef` is only a name — the orchestrator must know where each one lives.** Today nothing maps them: `case.a_dispute_orig` is read ad hoc, and `disputeDescription` arrives on the orchestrator's own inbound API request rather than being looked up at all. A vocabulary key naming any other text or column cannot be resolved, and the key then goes silently unanswered — which reaches `/contestability` as an ordinary missing fact. Needs: one resolver covering all three lanes (`SYSTEM_OF_RECORD` columns, `DOCUMENT` types, `MODEL_JUDGMENT` texts), which **fails loudly on a name it does not know** |

---

## Part 4 — `POST /facts/extract`

**Service:** `gcp-dispute-llm-service`. Called by the orchestrator to resolve every
`evaluationFacts` entry whose `source` is `DOCUMENT`.

| | |
|---|---|
| Method | `POST /facts/extract` |
| Auth | none today — **open item** |
| Statefulness | stateless per call; caches read documents by content digest |

### 4.1 Request

```jsonc
{
  "caseNumber":     "CASE-000123",
  "actionSequence": "1",
  "platform":       "nap",

  "requiredKeys": [
    { "key":      "delivery.deliveredDate",
      "type":     "value",                      // truth | value | measure
      "question": "On what date was the merchandise delivered?",
      "documents": [
        { "ref": "gs://wdp-evidence/CASE-000123/pod-1.pdf", "documentType": "proofOfDelivery" },
        { "ref": "gs://wdp-evidence/CASE-000123/pod-2.pdf", "documentType": "proofOfDelivery" }
      ]
    }
  ]
}
```

| Field | Purpose |
|---|---|
| `caseNumber` / `actionSequence` | correlation, and part of the document download path |
| `platform` | `nap` / `core` — builds the document-management download URL |
| `requiredKeys[].key` | the vocabulary key, straight from `evaluationFacts` |
| `requiredKeys[].type` | `truth` / `value` / `measure` — shapes how the model is asked to answer |
| `requiredKeys[].question` | the vocabulary's own wording. **This service holds no per-reason-code knowledge**; adding a condition adds no prompt here |
| `requiredKeys[].documents` | **required, non-empty.** The documents that might answer this key |

**Documents nest under the key, not the reverse.** A case may hold several documents of one type —
three proof-of-delivery PDFs for three shipments — and one question may need to be asked of all of
them. Keys-under-documents would repeat the same key under each parent with nothing saying they are
one question.

A document named by five keys is downloaded and read **once** — the reader caches by content digest.

### 4.2 Response

```jsonc
{
  "caseNumber": "CASE-000123",

  "facts": [
    { "key":          "delivery.deliveredDate",
      "status":       "KNOWN",
      "value":        "2026-06-05",
      "readQuality":  "DIGITAL_TEXT",
      "quote":        "Delivered 05 Jun 2026, signed for by J. Smith",
      "page":         2,
      "documentRef":  "gs://…/pod-1.pdf",
      "documentType": "proofOfDelivery" },

    { "key":          "delivery.deliveredDate",
      "status":       "KNOWN",
      "value":        "2026-06-11",
      "readQuality":  "VISION_HIGH",
      "quote":        "Delivery completed 11/06/2026",
      "page":         1,
      "documentRef":  "gs://…/pod-2.pdf",
      "documentType": "proofOfDelivery" },

    { "key":          "delivery.agreedDeliveryDate",
      "status":       "NOT_FOUND",
      "value":        null, "quote": null, "page": null,
      "readQuality":  "DIGITAL_TEXT",
      "documentRef":  "gs://…/pod-1.pdf",
      "documentType": "proofOfDelivery" }
  ],

  "skipped": [ "gs://…/annexe.tiff: unsupported document type" ],

  "provider": "openai", "model": "gpt-5.5", "promptVersion": "v4"
}
```

**One fact per (key, document).** Same key, two documents, two rows — even when they disagree. The
extractor never picks a winner: a model choosing which document to believe is the judgment we keep
out of producers everywhere else. The orchestrator dedupes identical values and forwards
disagreements, where the engine raises `CONFLICT` (CO9).

#### `status` — three states, and the third is the point

| Status | Meaning | Is it evidence? |
|---|---|---|
| `KNOWN` | value found and span-verified | yes |
| `NOT_FOUND` | the document was read whole and the value was not in it | **yes** — enough to resolve a ground FALSE |
| `UNRESOLVED` | the document could not be read | **no** — nobody looked |

Collapsing `NOT_FOUND` and `UNRESOLVED` into one blank is how a failed extraction becomes a silent
concession. **Anything unread is `UNRESOLVED`, never `NOT_FOUND`.**

#### This response is the evidence trail

`quote`, `page`, `documentRef` and `documentType` were deleted from the `/contestability` request
(C13) on the grounds that the orchestrator already holds them. **This is where it gets them**, and
under §1.5 it must persist this response alongside the decision, rejoinable by fact key.

### 4.3 Logic

- **Validate** — every key must carry at least one document; empty → `400`
- **Dedupe documents** by ref across keys, so one file is fetched and read once however many keys
  name it
- **Read each document whole** — no page cap. Digital text layer where present, OCR per page
  otherwise
- **Unreadable** — unsupported type, fetch failure, or text exceeding the model's context → name it
  in `skipped`; its keys return `UNRESOLVED`. **Never truncate silently**
- **Mask PAN** before anything reaches a prompt
- **One model call per document**, carrying every key that names it, each with its `question` and
  `type`
- **Verify the span** — the returned `quote` must appear in the text that was read. Failure discards
  the value and the key returns `NOT_FOUND`. *This is what makes a fact `EXTRACTED_VERIFIED` rather
  than merely extracted*
- **Set `readQuality` per fact** from the page the quote came from, not the document's worst page
- **Never pick a winner** — one fact per (key, document)
- **Classify nothing** — no corroboration, no evidence class. The engine derives those from the
  vocabulary
- **Set status** — `KNOWN`, `NOT_FOUND`, or `UNRESOLVED`

### 4.4 Decision log

| # | Decision | Reasoning |
|---|---|---|
| E1 | Documents nest under the key | A case may hold several documents of one type. Keys-under-documents repeats the same key under each parent with nothing saying they are one question |
| E2 | `documents` is required and non-empty; the "empty means look them up yourself" fallback is deleted | **The caller decides what is read; the service reads what it is told.** Discovery belongs to the orchestrator. Fetching a named ref is transport, not discovery — which is why `platform` stays |
| E3 | One fact per (key, document); the extractor never reconciles | A model choosing which document to believe is the judgment kept out of producers everywhere else. Disagreement surfaces as `CONFLICT` at the engine |
| E4 | `corroboration` removed from the response | C6 — evidence class is a property of the key, held in the vocabulary. The extractor classifying how contradictable its own output is was the model grading itself |
| E5 | `sourceQuality` renamed `readQuality` | Matches the `/contestability` request |
| E6 | The whole document is read; `MAX_PAGES_PER_DOCUMENT` is deleted | The cap was a read limit, not a call limit — pages beyond it were never OCR'd, so the model never saw them. It also penalised digital-text PDFs, where reading 120 pages costs nothing |
| E7 | An unread or partially-read document yields `UNRESOLVED`, never `NOT_FOUND` | `NOT_FOUND` is evidence — enough to resolve a ground FALSE. On a truncated read that is a concession on pages nobody looked at. Replaces the page cap as the outlier guard: fail loudly, never trim quietly |
| E8 | `documentsRead` deleted | After the page counts went, its only unique field was `contentHash` — see E9 |
| E9 | `contentHash` deleted | Traced end to end: the orchestrator passes it into `NarrativeRequest` and `dispute-llm-service` **declares the field but reads it nowhere**. Its intent — so the narrative argues from verified text — is served better by the orchestrator passing the `quote` directly |
| E10 | `fromCache` deleted | Debugging noise |
| E11 | `pagesRead` / `pagesTotal` deleted | Always equal once the whole document is read |
| E12 | `readQuality` is set from the quote's page, not the document's worst page | A document's worst page caps every fact read from it, including ones read off a clean page. See CO3 |
| E13 | Chunking deferred | Once the text exists, a long document fits a modern model's context. If it ever does not, the shape is already agreed: one candidate per chunk, `(key, document)` becoming `(key, document, pageRange)` — no new machinery |

### 4.5 Open items

| # | Item | Needs |
|---|---|---|
| EO1 | OCR spend is now unbounded per case | The page cap bounded it badly. Watch real merchant uploads; if huge scans are common, cap the **vision** path deliberately — digital text needs no cap |
| EO2 | Span verification is the whole of `EXTRACTED_VERIFIED` | It moves here from the rules engine (CO4). It must not weaken in the move. The engine's `TesseractVerifier` — an independent second read that caps to `VISION_LOW` on disagreement — should come across rather than be lost |
| EO3 | `documentType` still arrives from the caller | **Closed by Part 6** — `POST /documents/classify` decides which attachment is which, and runs before this endpoint |
| EO4 | No auth on the endpoint | Before this leaves the POC. Same as CO6, RO5 |

---

## Part 5 — `POST /facts/judge`

**Service:** `gcp-dispute-llm-service`. Called by the orchestrator to resolve every
`evaluationFacts` entry whose `source` is `MODEL_JUDGMENT`.

| | |
|---|---|
| Method | `POST /facts/judge` |
| Auth | none today — **open item** |
| Statefulness | stateless. Nothing fetched, nothing stored |

### 5.1 Request

```jsonc
{
  "caseNumber":     "CASE-000123",
  "actionSequence": "1",

  "sourceRef": "disputeDescription",
  "text":      "Cardholder states the goods never arrived and that the merchant has not responded…",

  "requiredKeys": [
    { "key":      "dispute.solelyAboutQuality",
      "type":     "truth",
      "question": "Does the dispute concern quality alone?" },

    { "key":      "dispute.statedAsFraud",
      "type":     "truth",
      "question": "Does the issuer state the transaction was fraudulent?" }
  ]
}
```

| Field | Where it comes from |
|---|---|
| `caseNumber` / `actionSequence` | correlation only |
| `sourceRef` | `/requirements` — **names** which text to judge over |
| `text` | the orchestrator resolves that name to actual content (RO6). Today `disputeDescription` arrives on the orchestrator's own inbound API request, not from a case column |
| `requiredKeys[].key` / `.type` / `.question` | `/requirements`, verbatim |

**One call per distinct text**, mirroring extract's one call per document.

#### `text` is case data, not the rulebook

Two texts exist in this system and they do opposite jobs:

| | What it is | Where it lives | Used by |
|---|---|---|---|
| **requirement text** | the rulebook passage, verbatim. Same on every case | the tree node | `/narrative`, after the decision |
| **`text` here** | what the issuer wrote about *this* dispute. Different every case | the case | `/facts/judge`, before the decision |

The rulebook passage is what *produced* the `question` at authoring time — the vocabulary records it
as `derivedFrom`. But at runtime the model needs something to judge **against**, and the rulebook
cannot know what this cardholder complained about. The requirement text is also not available at
this point in the flow: it only exists once `/contestability` has returned `citedGrounds`.

#### No endpoint generates this text

Where the issuer's narrative sits inside a document, read it with `/facts/extract`. Where it is
spread across structured fields, assemble it in code. Where it does not exist, the judged keys go
unanswered.

**It is never generated by a model.** A judgment over model-written text reads on the wire exactly
like a judgment over what the issuer actually wrote — two model layers deep, and the provenance
ceiling cannot see the second one.

### 5.2 Response

```jsonc
{
  "caseNumber": "CASE-000123",

  "judgments": [
    { "key":    "dispute.solelyAboutQuality",
      "status": "JUDGED",
      "value":  "false",
      "quote":  "the goods never arrived and the merchant has not responded" },

    { "key":    "dispute.statedAsFraud",
      "status": "UNRESOLVED",
      "value":  null, "quote": null }
  ],

  "refused": [
    "dispute.regardsValueAddedTax: the quote was not present in the text"
  ],

  "provider": "openai", "model": "gpt-5.5", "promptVersion": "v3"
}
```

| Field | Note |
|---|---|
| `status` | `JUDGED` / `UNRESOLVED`. **`JUDGED`, deliberately not `KNOWN`** — the wire says this is a model's reading of meaning, not a value read out of a document, and the engine's provenance ceiling turns on that difference |
| `value` | the answer, shaped by the key's `type` |
| `quote` | the sentence of the text that grounds the judgment, verified verbatim against it |
| `refused` | **never silent** — a judgment with no quote, or a quote absent from the text, is named here rather than dropped |
| `provider` / `model` / `promptVersion` | audit, as on extract |

**Unchanged from today.** Worth recording why, since extract needed several changes: this endpoint
never classified corroboration, so C6 removed nothing; it has no `readQuality` because the text is
already digital and there is no OCR band to report; `refused` already does what `skipped` does on
extract; and two statuses suffice because there is one text — no `NOT_FOUND` / `UNRESOLVED` split
to make.

### 5.3 Logic

- **Validate** — `text` non-empty, at least one key; otherwise `400`
- **Mask PAN** before anything reaches a prompt
- **One model call**, carrying the text and every key with its `question` and `type`
- **Verify the quote** — it must appear verbatim in the text. Failure discards the judgment and names
  it in `refused`
- **No quote at all** → same: discarded and named in `refused`
- **Answer not readable** as the key's `type` → `UNRESOLVED`
- **Set status** — `JUDGED` where a verified quote grounds the answer, `UNRESOLVED` otherwise
- **Classify nothing** — no provenance, no ceiling. The engine derives that from the vocabulary

The whole endpoint is one call and one guard: **an answer that cannot be pointed at in the text does
not count.** Without it a model returns fluent judgments about a dispute it inferred rather than read.

### 5.4 Decision log

| # | Decision | Reasoning |
|---|---|---|
| J1 | `type` added to each key | Same as R16 and E-side: shapes how the model is asked to answer. Most judged keys are `truth`, but not all |
| J2 | `disputeText` becomes `sourceRef` + `text` | `/requirements` names a text per key, and the vocabulary records a different one for some judged keys. A single unnamed field assumes every judged key reads the same thing, which is not true |
| J3 | One call per distinct text | Mirrors extract's one call per document |
| J4 | No endpoint builds the issuer narrative | A model judging model-written text is indistinguishable on the wire from a model judging the issuer. Where the text sits in a document, `/facts/extract` reads it; where it is structured, code assembles it; where it does not exist, the keys go unanswered |
| J5 | Response unchanged | It already returns `JUDGED` rather than `KNOWN`, already verifies the quote, already refuses out loud, and never classified corroboration |
| J6 | Judged facts reach `/contestability` as plain `{key, value}` | No `readQuality`, no status. The engine caps them because the **vocabulary** says the key is `MODEL_JUDGED` (C4). `status: JUDGED` is for the orchestrator's record, not for the engine |

### 5.5 Open items

| # | Item | Needs |
|---|---|---|
| JO1 | Non-English issuer text | `NAP` acquires into Europe, so member messages arrive in French, German, Spanish. Nobody has verified the model judges the source language reliably — and a judgment over text the model half-understood is worse than none. Every case also reaches a reviewer, who has to read it. Decide: a flag on this endpoint, a translation step, or an accepted limitation |
| JO2 | `disputeDescription` availability in production | **Closed.** WDP receives the issuer's documents from the network as part of the dispute data, attached to the case and classified as issuer-side. So the narrative is a **document-lane** key: `/documents/classify` finds it, `/facts/extract` reads it. It is no longer an inline field on any request. Requires a vocabulary change — `dispute.disputeDescription` moves from `MODEL_JUDGMENT` over an inline field to `DOCUMENT` with its own `documentType` |
| JO3 | No auth on the endpoint | Before this leaves the POC. Same as CO6, RO5, EO4 |

---

## Part 6 — `POST /documents/classify`

**Service:** `gcp-dispute-llm-service`. **New endpoint — does not exist today.**

`/requirements` names a document *type* (`sourceRef: "proofOfDelivery"`). A case arrives with N
merchant attachments and nothing says which file that is. Today the type comes from the
questionnaire generator's store, where POC documents are pre-labelled; production uploads will not
be. Without this, the orchestrator either guesses or extracts every key from every document — N×
the model cost, and the wrong document answering a key.

Runs **before** `/facts/extract` in the sequence.

| | |
|---|---|
| Method | `POST /documents/classify` |
| Auth | none today — **open item** |
| Statefulness | stateless; shares the reader's content-digest cache with `/facts/extract` |

### 6.1 Request

```jsonc
{
  "caseNumber":     "CASE-000123",
  "actionSequence": "1",
  "platform":       "nap",

  "documentTypes": ["proofOfDelivery", "policy", "orderConfirmation"],

  "documents": [
    { "ref": "gs://…/upload-1.pdf", "declaredSide": "MERCHANT" },
    { "ref": "gs://…/upload-2.pdf", "declaredSide": "MERCHANT" },
    { "ref": "gs://…/issuer-dispute.pdf", "declaredSide": "ISSUER" }
  ]
}
```

| Field | Where it comes from |
|---|---|
| `caseNumber` / `actionSequence` | correlation, and part of the download path |
| `platform` | `nap` / `core` — builds the document-management download URL |
| `documentTypes` | `/requirements` — the distinct `sourceRef` values of the `DOCUMENT`-lane keys |
| `documents[].ref` | the orchestrator lists the case's attachments from document management |
| `documents[].declaredSide` | `ISSUER` / `MERCHANT`, as WDP records it. The network assigns it when the dispute arrives |

**`declaredSide` scopes the classification, it does not merely hint at it.** It maps straight onto
`sourceSide` on `evaluationFacts`: an issuer document may answer `ISSUER`-side keys, a merchant
document `MERCHANT`-side keys. Two things follow — less work, because each document is only asked
about the types its side can supply; and a **real guard**, because a merchant document can never
answer an issuer-side key. Without it, nothing stops a merchant's own letter being read as the
issuer's dispute narrative — the merchant writing the other side's case.

**`documentTypes` makes this a closed-set classification.** The question is *"which of these files is
the proof of delivery?"*, not *"what is this file?"* Asking against a known set is far more reliable
than open-ended labelling, and the set is free — `/requirements` already named exactly the types this
condition needs.

`documents` entries are objects rather than bare strings so a field can be added later without a
breaking change.

### 6.2 Response

```jsonc
{
  "caseNumber": "CASE-000123",

  "classifications": [
    { "ref": "gs://…/upload-1.pdf",
      "matches": [
        { "documentType": "orderConfirmation", "quote": "Order Confirmation #4471", "page": 1 },
        { "documentType": "proofOfDelivery",   "quote": "PROOF OF DELIVERY — DHL",  "page": 4 }
      ] },

    { "ref": "gs://…/upload-2.pdf",
      "matches": [
        { "documentType": "policy", "quote": "Returns & Refunds Policy", "page": 1 }
      ] }
  ],

  "unclassified": [ "gs://…/upload-3.pdf: matched none of the requested types" ],
  "skipped":      [ "gs://…/upload-4.tiff: unsupported document type" ],

  "provider": "openai", "model": "gpt-5.5", "promptVersion": "v1"
}
```

#### One document, several types

A merchant may send the order confirmation, the delivery note and the returns policy as **one PDF**.
That is normal, not a conflict. Each match carries its **own** quote and page — the evidence that
this file plays *that* role, at *that* point in the document.

The orchestrator then lists the same ref under every lane it serves when calling `/facts/extract`.
It is downloaded and read **once** — the digest cache handles it.

Keep the two situations apart:

| | What it is | What happens |
|---|---|---|
| One document, several types | a combined PDF | referenced in every lane it serves. **Normal** |
| Several documents, same key, different values | two PODs, two delivery dates | `CONFLICT` — a person decides (CO9) |

#### `unclassified` vs `skipped`

The same distinction as `NOT_FOUND` vs `UNRESOLVED` on extract: `unclassified` means it was read and
matched nothing; `skipped` means it could not be read at all.

### 6.3 Logic

- **Validate** — at least one document and one requested type; otherwise `400`
- **Read each document whole** — the same reader as `/facts/extract`, cached by content digest, so a
  file classified here is not re-read when extraction runs
- **Unreadable** — unsupported type, fetch failure, context overflow → name it in `skipped`
- **Mask PAN** before anything reaches a prompt
- **Scope by side** — ask each document only about the `documentTypes` whose keys carry a matching
  `sourceSide`. A merchant document is never asked about an issuer-side type
- **One model call per document**, asking which of the scoped `documentTypes` it matches — a closed
  set, never open labelling
- **Verify each quote** — it must appear verbatim in the document. Failure drops that match
- **Return all matches** — one document may serve several roles, each with its own quote and page
- **No matches surviving verification** → `unclassified`
- **Never rank, never pick** — two documents matching one type both come back; the orchestrator sends
  both to extraction

### 6.4 Decision log

| # | Decision | Reasoning |
|---|---|---|
| D1 | The endpoint exists at all | `/requirements` names a document type and nothing today decides which attachment that is. Closes EO3 |
| D2 | Closed-set classification against `documentTypes` | Far more reliable than open labelling, and the set costs nothing — `/requirements` already named it |
| D3 | `declaredSide` (`ISSUER` / `MERCHANT`) is sent, and **scopes** the classification | **Revised.** Originally recorded as "no declared type — the platform carries none". WDP does carry one: the network classifies every attached document by side when the dispute arrives. It is coarse but reliable, and it maps onto `sourceSide`. A finer type label is still not available, which is why the endpoint exists |
| D4 | No confidence score | A model's confidence in its own classification is poorly calibrated. Model self-assessment is kept out everywhere else — `readQuality` comes from the OCR engine, evidence class from the vocabulary |
| D5 | `quote` verified verbatim, or the match is dropped | The third appearance of one guard: **an answer that cannot be pointed at in the source does not count.** A fluent model will label anything |
| D6 | Matches are a list per document | A merchant may send several document types as one PDF. That is one file serving several roles, not a conflict |
| D7 | Never rank, never pick a winner | Two documents matching one type both go to extraction. Disagreeing *values* are what become `CONFLICT`; two files of the same type are not a disagreement |
| D8 | Shares the reader and its digest cache with `/facts/extract` | A document classified here is read once, not twice |

### 6.5 Open items

| # | Item | Needs |
|---|---|---|
| DO1 | A document type not in `documentTypes` is invisible | The request only asks about types this condition needs. A merchant sending something useful for a *different* ground is never noticed. Acceptable while the set comes from `/requirements`; worth revisiting if merchants upload broadly |
| DO2 | Classification quality is unmeasured | There is no baseline for how often the right file is found. Needs measuring on real merchant uploads before extraction depends on it |
| DO3 | No auth on the endpoint | Before this leaves the POC. Same as CO6, RO5, EO4, JO3 |

---

## Part 7 — `POST /narrative`

**Service:** `gcp-dispute-llm-service`. The only orchestrator call that runs **after** the decision,
and the only consumer of `citedGrounds[].requirementText`.

| | |
|---|---|
| Method | `POST /narrative` |
| Auth | none today — **open item** |
| Statefulness | stateless. Nothing fetched, nothing stored |

### 7.1 Request

```jsonc
{
  "caseNumber":     "CASE-000123",
  "actionSequence": "1",
  "purpose":        "FILING",            // FILING | REVIEW

  "routing":         "CONTEST",          // carried so the prose matches. NEVER re-decided here
  "reasonCode":      "13.1",
  "currency":        "USD",
  "contestedAmount": "250.00",

  "responseReason":    "CREDIT_PROCESSED",
  "evidenceKind":      "CE03",
  "attachedDocuments": ["proofOfDelivery"],
  "merchant":          { "name": "…", "city": "…", "category": "Bus Lines" },
  "letterSections":    true,

  "grounds": [
    { "nodeId":          "G5",
      "anchorPassageId": "VISA-13.1-11.10.7.6",
      "requirementText": "The Merchandise was received by the Cardholder…",
      "facts": [
        { "key":   "delivery.deliveredDate",
          "value": "2026-06-05",
          "quote": "Delivered 05 Jun 2026, signed for by J. Smith" }
      ]
    }
  ]
}
```

| Field | Purpose |
|---|---|
| `purpose` | `FILING` writes prose for the issuer; `REVIEW` writes it for the reviewer |
| `routing` / `reasonCode` / amounts | so the prose **matches** the decision. Never so the model can revisit it |
| `responseReason` / `evidenceKind` | select the argument-shape guidance. Derived by the orchestrator from the ground that fired — never per reason code, never re-derived here |
| `attachedDocuments` | **the narrative may name these and no others.** A guardrail, not information |
| `merchant` | the only facts the merchant description may use |
| `letterSections` | also return the representment-letter sections |
| `grounds` | from `citedGrounds`, with each ground's facts and their verified quotes |

#### Facts nest under their ground

The letter argues **ground by ground** — each requirement quoted, then the facts that satisfy it.
`citedGrounds` already carries facts per ground, so flattening them into a separate list would make
this service re-associate what the engine already knew.

#### Each fact carries its `quote`

This is what `documentContentHash` was trying to achieve: the narrative argues from the text the span
was verified against, rather than a second reading that may differ. The orchestrator holds those
quotes from `/facts/extract`, so it passes them directly instead of a hash the service was meant to
look up — and never did (E9).

#### `restsOnUnconfirmedReading` is not sent

A ground met on a reading no scheme expert confirmed is a warning for the **reviewer**, not something
the issuer's letter can say. The orchestrator holds the flag on `citedGrounds` and builds the review
screen, so it shows the warning itself. Nothing here needs it.

### 7.2 Response

```jsonc
{
  "explanation": "The merchandise was delivered to the cardholder on 5 June 2026…",
  "comments":    "Proof of delivery is attached.",
  "notes":       "Delivery date read from a scanned document — verify before filing.",
  "summary":     "Contest on proof of delivery.",

  "merchantDescription": "Acme Travel Ltd is a bus line operating in Leeds…",
  "argumentParagraphs": [
    { "nodeId": "G5",
      "paragraph": "The Merchandise was received by the Cardholder on 5 June 2026, evidenced by…" }
  ],

  "provider": "openai", "model": "gpt-5.5", "promptVersion": "v7", "attempts": 2
}
```

**Unchanged from today.**

#### The rule the whole response is built on

> **Prose only. No amounts, no dates, no reason code, no decision.**

Every factual field in the questionnaire is derived by the **orchestrator** from the facts and the
ground that fired. If this response carried them, they would have to be checked against the facts
before use — **and that check is the derivation, written backwards.**

The model writes sentences. It never supplies a value anyone acts on.

| Field | Note |
|---|---|
| `explanation` / `comments` | the questionnaire's prose fields |
| `notes` | **for the reviewer, not the issuer. Never copied into a filing** |
| `summary` | queue-level one-liner |
| `merchantDescription` / `argumentParagraphs` | present only where `letterSections` asked for them |
| `argumentParagraphs[].nodeId` | keys each paragraph to its ground, so the letter assembles under that ground's quoted requirement. **A join key, never printed** — a rule identifier means nothing to an issuer's analyst |
| `attempts` | how many drafts the validator rejected before one passed |

A refused draft returns `DraftRejected` — **a content outcome, not an outage**, never a 5xx.

### 7.3 Logic

- **Validate** — `purpose` and `routing` present, at least one ground for a `CONTEST`; otherwise `400`
- **Mask PAN** before anything reaches a prompt
- **Select the guidance** from `responseReason` and `evidenceKind` — the argument *shape*, never per
  reason code
- **One model call**, carrying the grounds with their verbatim `requirementText`, the facts and quotes
  under each, and the merchant facts
- **Validate the draft** against four guards:
  - no fact that was not supplied — no invented dates, amounts or names
  - no document named that is not in `attachedDocuments`
  - no node id in any prose
  - the merchant description uses only the `merchant` facts given
- **Retry on failure**, counting `attempts`. Still failing → return `DraftRejected`
- **Never re-decide** — routing, amounts and grounds are carried so the prose matches them
- **Return prose only**

### 7.4 Decision log

| # | Decision | Reasoning |
|---|---|---|
| N1 | `decision` + `disposition` → `routing` | They collapsed into one field on `/contestability` (C8). The same value is carried here |
| N2 | `documentContentHash` deleted | E9 — the service declared the field and read it nowhere |
| N3 | Facts nest under their ground | The letter argues ground by ground, and `citedGrounds` already carries facts per ground. Two flat lists made this service re-associate what the engine knew |
| N4 | Each fact carries its verified `quote` | Achieves what `documentContentHash` intended — the narrative argues from the text the span was verified against, not a second reading that may differ |
| N5 | `restsOnUnconfirmedReading` not sent | It warns the reviewer, not the issuer. The orchestrator holds the flag and builds the review screen, so it shows the warning itself |
| N6 | Response unchanged | It is already prose-only, already validated, already returns `DraftRejected` as a content outcome |
| N7 | Prose only, permanently | Every factual field is derived by the orchestrator. A factual field here would have to be checked against the facts before use — and that check is the derivation, written backwards |

### 7.5 Open items

| # | Item | Needs |
|---|---|---|
| NO1 | The four draft guards are the only thing between a model and a filing | Every case is reviewed, so a bad draft is caught by a person — but the guards are what make the review a check rather than a rewrite. Worth measuring the rejection rate once real cases run |
| NO2 | Non-English filings | Where a European issuer files in its own language, the letter may need to be written in it. Related to JO1 |
| NO3 | No auth on the endpoint | Before this leaves the POC. Same as CO6, RO5, EO4, JO3, DO3 |

---

## Part 8 — `POST /api/v1/wdp/auto-contest`

**Service:** `auto-contest-orchestrator`. The entry point. **New controller** — the existing
`/api/v1/auto-contest` stays as it is; this one is WDP-only and generates no network response.

It is the only endpoint that calls the other five, in order.

| | |
|---|---|
| Method | `POST /api/v1/wdp/auto-contest` |
| Auth | none today — **open item** |
| Mode | **synchronous**, `200`. Kafka comes later, and the event will carry the same two fields |
| Side effects | writes a questionnaire row; attaches a representment letter to the case |

### 8.1 Request

```jsonc
{
  "caseNumber":     "000000000012345",   // i_case, varchar(15)
  "actionSequence": "01"                 // i_action_seq, varchar(2)
}
```

Two fields. Everything else is looked up from WDP.

#### What the existing contract carried and this one does not

| Field | Why it is gone |
|---|---|
| `platform` | WDP only. A selector with one real value reads as one that works |
| `documentReference` | `/documents/classify` decides which attachment is which; the caller no longer names one |
| `disputeDescription` | WDP receives the issuer's documents from the network as part of the dispute data, attached to the case and classified issuer-side. The narrative is therefore read like any other document — see 8.3 |

### 8.2 Response

```jsonc
{
  "caseNumber":     "000000000012345",
  "actionSequence": "01",

  "routing":    "CONTEST",         // CONTEST | ACCEPT | NOT_JUDGEABLE
  "reasonCode": "13.1",

  "currency":        "USD",
  "disputedAmount":  250.00,
  "contestedAmount": 250.00,
  "acceptedAmount":    0.00,

  "questionnaireId":     "Q-2026-000412",
  "representmentDocRef": "gs://wdp/cases/000000000012345/representment-letter.pdf",

  "missingDocuments": []
}
```

`questionnaireId` and `representmentDocRef` are **null** on `ACCEPT` and `NOT_JUDGEABLE` — nothing was
generated, and the response says so rather than returning a half-built artifact.

**No `citedGrounds`, no facts, no narrative text.** Operations reviews inside WDP — the attached
document and the questionnaire row. Returning the argument here would be a second copy of what was
just written to the case.

**No network response.** Out of scope for this endpoint.

#### Re-running the same case

| Artifact | Behaviour |
|---|---|
| Questionnaire row | the DB replaces it. Nothing to handle |
| Representment letter | the document store rejects a duplicate name. **Swallow it, return the existing ref, log it** |

The rejection must not fail the call: the questionnaire has already been replaced by then, so failing
would leave the case half-updated and report an error for work that succeeded. Worth stating
explicitly — the natural implementation propagates that exception.

### 8.3 Logic

- **Load the case** — case + action from WDP. Missing → `404`
- **Read the routing header** — network, reason code and `acquiringPlatform` off the case row
- **`GET /requirements`** — from Spring Cache, keyed network + reason code + platform
- **Not served** → return `NOT_JUDGEABLE`, stop. No further calls, no artifacts
- **Split `evaluationFacts`** by `source` into three lanes

**System-of-record lane**

- **Resolve each `sourceRef`** to a WDP column and read it. An unknown name **fails loudly** (RO6)

**Document lane**

- **List the case's attachments**, each with its WDP-assigned `declaredSide`
- **`POST /documents/classify`** with those documents and the distinct `documentTypes` the condition
  needs
- **Group** the document-lane keys by type, listing under each key every document classified as that
  type
- **`POST /facts/extract`**

**Judgment lane**

- **The issuer narrative is an extracted value, not an inbound field** — so judgment runs *after*
  extraction, using `dispute.disputeDescription` as its `text`
- **`POST /facts/judge`** — skipped entirely if that text was never obtained

**Decide and deliver**

- **Assemble the flat fact list** — `{key, value}`, plus `readQuality` on document-read facts. The
  same value from two documents is sent once; different values are both sent, so the engine raises
  `CONFLICT`
- **`POST /contestability`**
- **Log a warning** where the response's `treeVersion` differs from the cached one — a stale cache
  means facts were resolved against the wrong key list (§1.2)
- **`ACCEPT` or `NOT_JUDGEABLE`** → return with null artifacts
- **`CONTEST`** → `POST /narrative`, then
  - **write the questionnaire row** — upsert, replacing any prior row
  - **render the letter and attach it to the case** — swallow a duplicate-name rejection and return
    the existing ref
- **Persist the extraction record** alongside the decision, rejoinable by fact key (§1.5) — pre-arb
  needs the quotes and document refs the engine no longer holds
- **Return** routing, amounts, `questionnaireId`, `representmentDocRef`

> **Two ordering constraints:** classify before extract, and **extract before judge**. The second only
> became true when the issuer narrative moved into the document lane.

### 8.4 Decision log

| # | Decision | Reasoning |
|---|---|---|
| W1 | A new controller rather than changing `/api/v1/auto-contest` | This lane is WDP-only and generates no network response. Existing callers keep working unchanged |
| W2 | Request is `caseNumber` + `actionSequence` only | WDP carries a case number and an action sequence and the orchestrator looks the case up. The future Kafka event carries the same two fields |
| W3 | Synchronous `200` | Kafka comes later; until then the caller learns the outcome and where the artifacts went |
| W4 | `disputeDescription` removed from the request | WDP receives issuer documents from the network attached to the case. The narrative is a **document-lane** key: classify finds it, extract reads it. Closes JO2 |
| W5 | `platform` removed | WDP only |
| W6 | `documentReference` removed | `/documents/classify` decides which attachment is which |
| W7 | Response carries artifact references, not the argument | Operations reviews in WDP. Returning grounds and facts would duplicate what was just written to the case |
| W8 | A duplicate-name document rejection is swallowed | The questionnaire is already replaced by that point. Failing would leave the case half-updated and report an error for work that succeeded |
| W9 | No network response generated | Out of scope for this endpoint |

### 8.5 Open items

| # | Item | Needs |
|---|---|---|
| WO1 | `dispute.disputeDescription` must move lanes in the vocabulary | From `MODEL_JUDGMENT` over an inline field to `DOCUMENT` with its own `documentType`. Until then the judgment lane has no text |
| WO2 | Latency is four model round-trips deep | Classify, extract, judge, narrative — synchronous. Measure it before the caller's timeout is set, and before Kafka makes it invisible |
| WO3 | A partial failure mid-sequence | Extraction succeeds, narrative fails: the case has facts but no letter. Decide whether to return the decision with null artifacts, or fail the call |
| WO4 | No auth on the endpoint | Before this leaves the POC. Same as CO6, RO5, EO4, JO3, DO3, NO3 |

---

## Part 9 — Data model

The tables the seven endpoints read and write. Added one at a time, in the same spirit as the
endpoints: what each column is for, what values it may hold, and which endpoint depends on it.

### 9.0 Scope

**Read**

| Table | Endpoint | Supplies |
|---|---|---|
| `wdp."case"` | 8 | network, reason code, `c_acq_platform`, amounts, dates, merchant |
| `wdp.action` | 8 | action sequence, reason code, reported date |
| WDP documents | 6, 4 | attachment refs and the issuer/merchant side. **POC: artificial documents carry the same two categories** |
| `artifact.fact_key` | 2, 3 | **the vocabulary** — see 9.1 |
| `artifact.requirement`, `artifact.requirement_fact_key` | 2, 3 | the register the tree is assembled from |
| `artifact.tree`, `artifact.tree_node` | 2, 3 | tree selection and evaluation |
| `artifact.corpus_passage` | 2 | `requirementText` on `citedGrounds` |
| `artifact.acquiring_platform_region` | 2, 3 | `NAP` / `CORE` → region |
| `artifact.questionnaire_edition` / `_path` / `_field` / `_option` | 8 | the questionnaire **spec** |

**Write** — `autocontest.decision`, `decision_ground`, `decision_fact`, `decision_amount`,
`model_call`.

**To retire** — `autocontest.submission_record` (Part 8 generates no network response),
`amount_contribution` (the new response prices per ground only), `review_queue_entry` (operations
reviews in WDP, not in an orchestrator queue).

**Not needed for the POC** — a generated-questionnaire table. WDP has one in production; the POC
logs the questionnaire instead.

### 9.1 `artifact.fact_key` — the vocabulary

The most load-bearing table in the estate. Both `/requirements` and `/contestability` depend on it,
and **every ceiling in the system is read from it**.

#### Runtime columns

| Column | Type | Allowed values | Used by | Purpose |
|---|---|---|---|---|
| `key` | VARCHAR(128) | dotted, `delivery.deliveredDate` | all | identity. Unique |
| `status` | VARCHAR(16) | `KEY` \| `GAP` | 3 | `GAP` = declared unobtainable; dropped from `evaluationFacts` (R9) |
| `type` | VARCHAR(32) | `truth` \| `value` \| `measure` | 3, 4, 5 | → `type`. Shapes how the model is asked. **Only a `truth` can be false** |
| `unit` | VARCHAR(32) | `MONEY` \| `DAYS` \| `PERCENT` \| null | 2 | measure comparability. A measure without one cannot be checked against another |
| `origin` | VARCHAR(32) | `SYSTEM` \| `DOCUMENT` \| `MODEL` | 2, 3 | → `source`. Maps to provenance: `SYSTEM`→`COMPUTED`, `DOCUMENT`→`EXTRACTED_VERIFIED`, `MODEL`→`MODEL_JUDGED` |
| **`source_ref`** | VARCHAR(128) | `case.a_dispute_orig` | 3 | **NEW.** → `sourceRef` for SYSTEM keys. Required where `origin = 'SYSTEM'` |
| `document_type` | VARCHAR(64) | `proofOfDelivery`, `policy`, `disputeDescription`, … | 3, 4, 6 | → `sourceRef` for DOCUMENT and MODEL keys; the `documentTypes` set for classify |
| `source_side` | VARCHAR(16) | `ISSUER` \| `MERCHANT` \| `EITHER` \| null | 3, 6 | → `sourceSide`. Scopes classification against `declaredSide` |
| `inputs` | TEXT, CSV | fact keys | 2, 3 | the **derived-from graph** — see below. Has data; **not yet wired** |
| `question` | TEXT NOT NULL | prose | 3, 4, 5 | → `question`. Doubles as the extraction and judgment prompt |
| **`evidence_class`** | VARCHAR(24) | `ON_DOCUMENT_FACE` \| `CROSS_CHECKABLE` \| `MERCHANT_CLAIM` | 2 | **NEW.** Moved off the request (C6). ~80 keys to classify (CO2) |
| `approved_by` | VARCHAR(128) | name \| null | 2 | non-null = the reading was approved. **The sole sanction signal** → `restsOnUnconfirmedReading` |
| `approved_at` | DATE | | 2 | when |

#### Authoring-only columns

| Column | Type | Purpose |
|---|---|---|
| `reading` | TEXT | the operationalisation — **our** reading versus the rulebook's words. Runtime never reads it, but **its presence is what makes an approval required**, and the runtime reads that approval |
| `note` | TEXT | "how people get this key wrong". Also carries the gap narrative |
| `derived_from` | TEXT NOT NULL | the rulebook passage that made the key necessary. Traceability |

#### `inputs` — the derived-from graph

```
key    = authorization.obtained
inputs = authorization.found, authorization.approved
```

`/requirements` returns **base keys only** — the leaves. `authorization.obtained` never appears on
either wire: the orchestrator resolves the two columns, and the engine computes
`found && approved` itself (C2, C3).

It also makes `source_ref` honest: **a key with exactly one source field is base; anything composite
is derived** (R6).

#### `evidence_class` — what could contradict this fact?

| Value | Contradicted by | May decide a ground? |
|---|---|---|
| `ON_DOCUMENT_FACE` | the document's own face — a carrier status of `DELIVERED` | yes |
| `CROSS_CHECKABLE` | another system — a delivery date against the sale date | yes |
| `MERCHANT_CLAIM` | **nothing** — a recipient address, where the platform holds no cardholder address to compare | **no** |

It stops a ground being decided on the merchant's unverifiable say-so in a dispute the merchant is a
party to. Per-key, not per-case — which is why it belongs here and not on the request.

#### Where the keys come from

`/requirements` collects from the **tree**, not from this table:

1. walk the tree's node predicates, `appliesWhen` scopes and branch predicates → the keys this
   condition names
2. for each, walk `inputs` down until a key has none → the base keys
3. annotate each from this table → `source`, `sourceRef`, `sourceSide`, `type`, `question`

**Inputs are not in the tree.** The tree names the rule-level concept the rulebook uses; the
vocabulary holds the decomposition. That separation keeps a tree valid when *how* a derived key is
computed changes.

#### Migration

```sql
ALTER TABLE artifact.fact_key
    ADD COLUMN source_ref     VARCHAR(128),
    ADD COLUMN evidence_class VARCHAR(24),
    DROP COLUMN approval_licensed;

ALTER TABLE artifact.fact_key
    ADD CONSTRAINT fact_key_source_ref_required
    CHECK (origin <> 'SYSTEM' OR source_ref IS NOT NULL);
```

The constraint enforces R6 at insert time: a SYSTEM key names exactly one source field, and anything
composite fails rather than being written as an unresolvable `sourceRef`.

#### Decision log

| # | Decision | Reasoning |
|---|---|---|
| V1 | **+ `source_ref`** | `/requirements` returns it for SYSTEM keys and no column held it. `source` existed in V11 — *"field / formula / document it comes from"* — and **V23 dropped it**. RO6 is therefore worse than first recorded: the orchestrator lacks a resolver *and* the data it would resolve against was deleted |
| V2 | **+ `evidence_class`** | C6 moved it off the request into the vocabulary. Asking the extractor to classify how contradictable its own output is was the model grading itself |
| V3 | **− `approval_licensed`** | 1:1 with `approved_by` (V50 backfilled it onto exactly the 35 keys that carry an approval); only its **non-blankness** is ever read; the FIGHT→ASSEMBLE_REVIEW cap it drove is moot under C7. The drift check it was intended for — licensed composition versus the predicate actually written — **was never built** |
| V4 | `inputs` kept | It has data and is the enabler for C2/C3/R6. Unused today only because `inputsOrEmpty()` has no callers — that is Phase 1's job, not dead weight |
| V5 | `reading`, `note`, `derived_from` kept | Authoring-lane, but `reading` is the trigger that makes an approval necessary, and the runtime reads that approval. `derived_from` is the traceability back to the rulebook |
| V6 | Currency convention | V23 dropped `currency_fact_key`; MONEY facts resolve currency by convention from `transaction.currency`. Part 3's example says `case.currency` — **pick one name** |

#### Open items

| # | Item | Needs |
|---|---|---|
| VO1 | `source_ref` must be backfilled for every SYSTEM key | The constraint cannot be added until it is. ~68 SYSTEM-origin keys |
| VO2 | `evidence_class` must be set for ~80 keys | Same as CO2. `source_side` is a hint, not the answer |
| VO3 | `inputs` must be complete before `/requirements` can return base keys | Any composite key without inputs will fail the `source_ref` constraint, which is the intended forcing function |
| VO4 | `transaction.currency` versus `case.currency` | One name. V6 |

---

### 9.2 `prompt_version` — the prompt store

**Was `artifact.fact_judge_provider`.** Renamed, generalised, and **moved to
`dispute-llm-service`'s own schema.**

Holds the system prompt and user prompt template for every model call the estate makes. One row per
prompt per version; versions are never deleted, only retired — a decision taken in March must still
be explicable after the prompt is rewritten in April.

#### The split this table exists to draw

`fact_key` holds the **legal reading**: the question, the boundary, the interpretation an SME rules
on, shared by every reason code that names the key. A prompt is **engineering craft** and changes
weekly.

Held in one row, `updated_at` cannot distinguish a prompt tweak from a moved legal boundary — and a
moved boundary changes every reason code reading that key. `dispute.statedAsFraud` is read by five
conditions; `cardholder.withdrawalOnFile` by nine.

#### Columns

| Column | Type | Allowed values | Purpose |
|---|---|---|---|
| `id` | BIGINT | | identity |
| **`purpose`** | VARCHAR(24) NOT NULL | `JUDGE` \| `EXTRACT` \| `CLASSIFY` \| `NARRATIVE` | **NEW.** Which model call this prompt serves |
| `fact_key` | VARCHAR(128) | a vocabulary key, **null** for the three purpose-level prompts | which key this prompt answers. Only `JUDGE` is per-key |
| `version` | INTEGER NOT NULL | | increments on **any** change to prompt, schema, model or params. Not a timestamp: the decision record cites a version, and a version must mean one exact set of inputs |
| `status` | VARCHAR(16) NOT NULL | `DRAFT` \| `ACTIVE` \| `RETIRED` | one `ACTIVE` per (purpose, fact_key) |
| `model` | VARCHAR(64) NOT NULL | | |
| `params` | JSONB | | temperature and the rest |
| **`system_prompt`** | TEXT NOT NULL | | |
| **`user_prompt_template`** | TEXT NOT NULL | | |
| `input_scope` | JSONB NOT NULL | | what enters the context window **and what is excluded**. The exclusion is the load-bearing half — an issuer-side fact read from merchant records is the defect that made `dispute.statedAsFraud` wrong |
| `output_schema` | JSONB NOT NULL | | **must permit `UNKNOWN` and must require a citation.** A schema offering only true/false makes the model guess when the text is thin, and the guess arrives indistinguishable from a read fact |
| `contract_snapshot` | TEXT NOT NULL | | the key's `question` as it stood when the prompt was written. **Plain text, not a hash** — a developer diffs it by eye. When the question moves and this does not, the prompt is answering a question nobody asked any more |
| `note` | TEXT | | |
| `approved_by` / `approved_at` | VARCHAR(128) / TIMESTAMP | | **engineering** approval that the prompt implements the contract. **Not a scheme ruling, and never to be reported as one** |
| `created_at` / `retired_at` | TIMESTAMP | | never deleted, only retired |

#### Invariants, in DDL rather than application code

A prompt row can be written by a migration, by the authoring API, or by hand — an invariant that
holds on only one of those paths is not an invariant.

- **one `ACTIVE` per (purpose, fact_key)** — partial unique index. Two answers to one question is
  worse than none: which one produced the value?
- **`JUDGE` rows only on a `MODEL`-origin key, never on a `GAP`** — trigger, because a CHECK cannot
  see `fact_key`. A GAP has no producer by definition, so a prompt against one manufactures a value
  the register says cannot be known; a `SYSTEM` or `DOCUMENT` key with a judge row means two
  producers disagree about where the value comes from
- **never delete, only retire** — trigger on DELETE

#### Which decision used which prompt

`autocontest.decision_fact` records `judge_provider_id` and `judge_provider_version`, and
`autocontest.model_call` records `prompt_version` per call. Both should widen to cover all four
purposes.

#### Migration

```sql
ALTER TABLE artifact.fact_judge_provider RENAME TO prompt_version;

ALTER TABLE artifact.prompt_version
    ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT 'JUDGE',
    ALTER COLUMN fact_key DROP NOT NULL;

-- then move the table to dispute-llm-service's own schema
```

#### Decision log

| # | Decision | Reasoning |
|---|---|---|
| P1 | Kept | It is exactly the prompt store the estate needs, already versioned, already cited by the decision record |
| P2 | Renamed and generalised with `purpose` | It covered `/facts/judge` only. There are now four model purposes — classify, extract, judge, narrative — and only judge is per-fact-key. One store beats three more tables |
| P3 | Moved out of `artifact` into `dispute-llm-service` | The only consumer is the LLM service. Shipping a system prompt through `/requirements` → orchestrator → `/facts/judge` is three hops for something only the last one uses. The `fact_key` / prompt split still holds — the boundary is now between services rather than between tables |
| P4 | `contract_snapshot` stays plain text | Drift between the key's `question` and the prompt written against it must be visible by eye, to a developer and to an SME. A hash tells you *that* it moved, not *what* moved |
| P5 | `output_schema` must permit `UNKNOWN` and require a citation | A true/false-only schema makes the model guess on thin text, and the guess is indistinguishable from a read fact — the failure the four-valued logic exists to prevent |

#### Open items

| # | Item | Needs |
|---|---|---|
| PO1 | Prompts for `EXTRACT`, `CLASSIFY` and `NARRATIVE` do not exist as rows | They live in code today. Backfill them as `purpose`-level rows so all four are versioned the same way |
| PO2 | `decision_fact.judge_provider_*` and `model_call.prompt_version` are judge-shaped | Widen so a decision can cite which extract, classify and narrative prompts ran |
| PO3 | The cross-table trigger cannot follow the table across services | Once `prompt_version` leaves `artifact`, the "`JUDGE` only on a MODEL-origin key" trigger has no `fact_key` to read. It becomes an application check, or the vocabulary is replicated |

---

### 9.3 Document reading store — `dispute-llm-service`

Three tables. Two are keyed by **content hash** and shared across cases; one is per case.

The principle they are built on: **store the text, never the facts.** Byte-identical documents have
identical text, so a *reading* is safe to share between disputes. A fact is the answer to a
case-specific question, and sharing those would serve one dispute's conclusions to another.

#### `document_reading` — what the bytes say

| Column | Type | Allowed values | Purpose |
|---|---|---|---|
| `content_hash` | TEXT PRIMARY KEY | sha256 | identifies the **bytes**, not the file |
| `extracted_text` | TEXT NOT NULL | | the whole document, page markers included: `[page 1]\n…` |
| `page_quality` | TEXT NOT NULL | JSON `{"1":"DIGITAL_TEXT","2":"VISION_HIGH"}` | per-page read quality |
| `page_count` | INT | | |
| `read_quality` | VARCHAR(16) | `DIGITAL_TEXT` \| `VISION_HIGH` \| `VISION_LOW` | the **worst** page. A document is only as trustworthy as its worst page |
| `read_at` | TIMESTAMPTZ | | |

**Write-once.** The stored text is what every quote was verified against; a second writer could
replace it with a reading nothing checked. `INSERT … ON CONFLICT DO NOTHING`, first write wins.

**No separate page table.** The page text is already in `extracted_text` behind its markers — to find
a quote's page, locate it and walk back to the last marker. The only thing pages add that the blob
cannot is **per-page quality**, and that is a small map, not a second copy of the text. It is what
E12 needs: a fact read off a clean page is not capped by a blurry annexe elsewhere in the document.

#### `document_classification` — what the bytes are

| Column | Type | Allowed values | Purpose |
|---|---|---|---|
| `content_hash` | TEXT NOT NULL | | FK to `document_reading` |
| `document_type` | VARCHAR(64) NOT NULL | `proofOfDelivery`, `policy`, … | the type asked about |
| `matched` | BOOLEAN NOT NULL | | **asked and matched**, or **asked and it is not one** |
| `quote` | TEXT | | the verified span proving the type. Null where `matched = false` |
| `page` | INT | | where the quote was found |
| `prompt_version` | INT | | which classify prompt answered |
| `classified_at` | TIMESTAMPTZ | | |
| | | | PRIMARY KEY (`content_hash`, `document_type`) |

**`matched` is stored, not only the hits.** Without it, "never asked about this type" and "asked, and
it is not one" look identical — and every run re-classifies everything.

**Grows, unlike `document_reading`.** A new condition asks about a type nobody asked before, and a
row is added. That is why it is a separate table: one is immutable, the other accumulates, and a
single `UPDATE` over both would overwrite the guarantee the first one exists to give.

One document may match several types — a merchant may send the order confirmation, the delivery note
and the policy as one PDF (D6). That is several rows, all `matched = true`.

#### `case_document` — which attachment is which

| Column | Type | Purpose |
|---|---|---|
| `case_number` | VARCHAR(15) NOT NULL | |
| `action_sequence` | VARCHAR(2) NOT NULL | |
| `document_ref` | TEXT NOT NULL | the case attachment's path |
| `content_hash` | TEXT NOT NULL | joins to the two shared tables |
| `declared_side` | VARCHAR(16) | `ISSUER` \| `MERCHANT`, as WDP records it |
| `seen_at` | TIMESTAMPTZ | |
| | | PRIMARY KEY (`case_number`, `action_sequence`, `document_ref`) |

**Per case, never shared.** A hash identifies content; a ref identifies an attachment. The same PDF
can sit on many cases under different refs, and a merchant re-uploading to the same ref changes the
content behind it — this table is where that shows up.

#### Decision log

| # | Decision | Reasoning |
|---|---|---|
| X1 | Text keyed by content hash and shared; facts never shared | Byte-identical documents have identical text. A fact is the answer to a case-specific question |
| X2 | `document_reading` is write-once | The stored text is what every quote was verified against. A second writer could replace it with a reading nothing checked |
| X3 | No per-page text table | Page text is already in `extracted_text` behind its markers. Only per-page **quality** is missing, and that is a small JSON map |
| X4 | `page_quality` added | E12 — `readQuality` comes from the page the quote was found on, not the document's worst page |
| X5 | Classification kept in its own table | Different write time, and different mutability: one table is immutable, the other accumulates as new conditions ask about new types. Considered merging it as a JSON column and rejected once the two mutability rules had to live on one row |
| X6 | `matched` stored, not only hits | Otherwise "never asked" and "asked and no" are indistinguishable, and every run re-classifies |
| X7 | `case_document` separate from the hash-keyed tables | A ref is a case attachment; a hash is content. The same PDF on two cases has two refs and one hash |

#### Open items

| # | Item | Needs |
|---|---|---|
| XO1 | **`extracted_text` is stored unmasked** | `reader.py` writes `document.text()` straight from OCR; PAN masking happens later, at prompt time. M7 says no PAN persists into the *decision store* — this is not that store, so it sits outside the rule's wording while being exactly the risk it exists to prevent. A card number on a scanned receipt would persist here indefinitely, keyed by hash and shared across cases. **Mask before `put`, not before the prompt** |
| XO2 | A cached classification is only valid for the types that were asked | Covered by `matched`, but the lookup must check "was this (hash, type) pair asked at all" rather than "did it match" |
| XO3 | Re-upload to the same ref | `case_document` will hold a stale `content_hash` until the case is re-run. Decide whether to re-hash on every run or trust the ref |

---

## Endpoints not yet reviewed

Nothing is listed here until we have reviewed and finalised it. Each endpoint we settle becomes a
new Part, in the same five-subsection shape.

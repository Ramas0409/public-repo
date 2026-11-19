# The orchestrator (`chargeback-auto-response`): the office-owned changes, by hand

Do these after copying `copy/` over the office tree: the hunks use classes that `copy/` adds (`util.LogSafe`,
`util.LogContext`, `exception.ServiceStateException`, `CaseRow.documentName`). Do them in this order. Each hunk says
which file it is written against:

- **the office's own copy under `docs/office/`**, where the owner supplied one (`ExhibitPages`, `LlmClient`,
  `RestInvoker`, `RestInvokerQuestionnaireTarget`). Those copies date from 4 to 17 October. Their imports name
  packages from before the package moves of fix-issues (for example `model.CaseRow`, not `model.casefile.CaseRow`),
  so **match each hunk on the method body, not the line number, and keep the office's current imports**;
- **the POC's class**, where no office copy is in hand (`ClassifierContestImpl` at the port's base, the correlation
  door, `logback-spring.xml`).

## 1. A7: the class that takes the incoming `v-correlation-id` (open)

**First find out which class it is.** In the office's `src/main`:

```
grep -rln "v-correlation-id\|V_CORRELATION_ID" src/main/java
grep -rln "OncePerRequestFilter\|HandlerInterceptor" src/main/java
```

**Case A: the office runs the POC's `config/CorrelationFilter` and `util/RequestCorrelation`.**
- Copy both whole from `a7/src/main/java/...` over the office's.
- Then make the one check: the office's `ApplicationConstants.V_CORRELATION_ID` is `"v-correlation-id"`, the same
  header as `RequestCorrelation.HEADER`.

**Case B: the office has its own class.** Make three changes in it, and add one method to the office's
`RequestCorrelation`.

1. **The guard (A10).** A caller's id is kept only where it matches `^[A-Za-z0-9._:-]{1,64}$`; otherwise the class
   makes its own and logs the length alone, never the value. Add to the office's `util/RequestCorrelation`:

   ```java
   private static final java.util.regex.Pattern ACCEPTED = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,64}");

   /** The caller's id where every line may print it and the llm's usage table may hold it, else null. */
   public static String accepted(String id) {
       return id != null && ACCEPTED.matcher(id).matches() ? id : null;
   }
   ```

2. **The door itself**, where the office reads the header. Before:

   ```java
   String id = <the office's read of the v-correlation-id header>;
   <the office's put of id into the MDC as correlationId, or a new UUID where none came>
   ```

   After:

   ```java
   LogContext.clear();
   String incoming = request.getHeader(ApplicationConstants.V_CORRELATION_ID);
   String id = RequestCorrelation.start(RequestCorrelation.accepted(incoming));
   response.setHeader(ApplicationConstants.V_CORRELATION_ID, id);
   if (incoming != null && RequestCorrelation.accepted(incoming) == null) {
       log.warn("event=corr.replaced len={}", incoming.length());
   }
   ```

   `RequestCorrelation.start(null)` makes a new UUID, as the POC's does. If the office's `start` differs, use its own
   "put the id, or a new UUID" step with the accepted value.

3. **The end of the request,** in a `finally` round the chain:

   ```java
   } finally {
       RequestCorrelation.clear();
       LogContext.clear();
   }
   ```

   `LogContext.clear()` removes every key the controllers and the run put (`platform`, `caseNumber`,
   `actionSequence`, `caseKey`, `runId`), so a pooled thread never carries one request's case into the next.

## 2. `letter/ExhibitPages` (against `docs/office/ExhibitPages.java`)

**E1. The imports.** After `import com.wp.wdp.chargeback.auto.response.service.DocumentService;` add:

```java
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;
import com.wp.wdp.chargeback.auto.response.util.LogSafe;
```

**E2. The attached line** (`assemble`, office line 118). Before:

```java
            log.info("attached {} page(s) from the document store; the filing is {} page(s)", appended,
                    out.getNumberOfPages());
```

After:

```java
            log.debug("event=letter.attached pages={} total={}", appended, out.getNumberOfPages());
```

**E3. The append failure** (office line 123). Before:

```java
            throw new IllegalStateException("the attachment pages could not be appended: "
                    + exception.getMessage(), exception);
```

After:

```java
            throw ServiceStateException.appending("the attachment pages could not be appended",
                    exception.getMessage(), exception);
```

**E4. A source that does not close** (office line 131). Before:

```java
                    log.warn("an attachment source did not close: {}", exception.getMessage());
```

After:

```java
                    log.warn("event=letter.source.failed error={}", exception.getClass().getSimpleName(),
                            LogSafe.of(exception));
```

**E5. The three "read back" throws** (`textOf(byte[])`, `textOf(byte[], int)` and `pageCount`, office lines 232, 244
and 252). Make the same change in each. Before:

```java
            throw new IllegalStateException("the letter could not be read back: " + exception.getMessage(), exception);
```

After:

```java
            throw ServiceStateException.appending("the letter could not be read back",
                    exception.getMessage(), exception);
```

**E6. Not BASE64** (`fetchDocument`, office line 264). Before:

```java
            throw new IllegalStateException("the document store returned a body that is not BASE64: "
                    + exception.getMessage(), exception);
```

After:

```java
            throw ServiceStateException.appending("the document store returned a body that is not BASE64",
                    exception.getMessage(), exception);
```

**E7. The document API call, S1 and S3** (`documentApiBase64`, office lines 269 to 290). Before:

```java
            log.info("Document Base64 Response: {}", responses);

            if (responses != null && !responses.isEmpty()) {
                DocumentBase64Response response = responses.get(0);
                if (response != null && response.getBase64Data() != null) {
                    return response.getBase64Data();
                }
            }
            log.warn("No base64 data returned for reference: {}", reference);
            return null;
        } catch (Exception exception) {
            log.error("Failed to fetch document from API for reference {}: {}", reference, exception.getMessage(), exception);
            return null;
        }
```

After (S1: the line that printed every document as base64 goes; S3: the document by its place in the case, never
the S3 key, and the clean trace, once, here where the failure is handled):

```java
            if (responses != null && !responses.isEmpty()) {
                DocumentBase64Response response = responses.get(0);
                if (response != null && response.getBase64Data() != null) {
                    return response.getBase64Data();
                }
            }
            log.warn("event=document.fetch.empty doc={}", row.documentName(reference));
            return null;
        } catch (Exception exception) {
            log.warn("event=document.fetch.failed doc={} error={}", row.documentName(reference),
                    exception.getClass().getSimpleName(), LogSafe.of(exception));
            return null;
        }
```

About E7:
- **The two event names are new.** The POC's `ExhibitPages` reads a folder, so it has no such call or lines.
- **The level is WARN, not ERROR.** The path is handled: the exhibit is skipped and the run records a problem, as
  before.
- **`row.documentName(reference)`** prints `<side>#<n>` (for example `merchant#2`). It comes from the copied `CaseRow`.

**Not changed:**
- the strings `append` adds to the run's problems (`"exhibit … could not be fetched (…)"`). They are stored in the run
  record and are not log lines, and this port moves no stored value;
- the office's `RULE` colour (the register's drift note stands).

## 3. `service/impl/ClassifierContestImpl` (against the POC's class at the port's base, `e9b4cd72`)

**The office's copy under `docs/office/` is from 5 November.** That is before letter-fix stage 2, which the office has
since taken whole, so it is not the class the office runs now. Nothing touched this class between the letter-fix merge
(`edc3f57b`) and the base. So **the office's current class should be the POC's at the base, and the simplest port is
to copy the head's file whole**: `reference/src/main/java/.../service/impl/ClassifierContestImpl.java`.

**Before copying, confirm** that the office's current file equals the base's: `reference/ClassifierContestImpl.base.java`
in this package is the POC's class at `e9b4cd72`; compare the office's file with it.
- **Equal apart from whitespace:** copy whole.
- **Not equal:** apply the hunks below, which are the branch's whole diff of the class.

C1. Imports, after `import com.wp.wdp.chargeback.auto.response.service.register.SubFormFieldLists;`:

```java
import com.wp.wdp.chargeback.auto.response.util.LogSafe;
import com.wp.wdp.chargeback.auto.response.util.LogContext;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;
```

C2. `contest(...)`. Before:

```java
    public ContestResult contest(AutoContestRequest request) {
        CaseRow row = CaseRequestMapper.toCase(request);
        OffsetDateTime createdAt = OffsetDateTime.now();
        String runLabel = createdAt.toLocalDateTime().format(STAMP);
        try {
            PreparedCase prepared = prepare(row, false);
            ContestResult result;
            if (prepared.isJudgeable()) {
                withSecondRun(prepared, createdAt);
                Nomination nomination = stage.nominate(row, prepared.getCatalogue(), prepared.getPayload(),
                        prepared.getIssuer(), prepared.getLimits(), prepared.getRecords(), prepared.getSecondRun());
                result = conclude(request, prepared, nomination, createdAt, runLabel);
            } else {
                result = notJudgeable(request, prepared, createdAt, runLabel);
            }
            save(result, prepared);
            log.info("case {}: classifier decision {} / {}", row.getCaseNumber(), result.getDecision(),
                    result.getLetterMode());
            return result;
```

After:

```java
    public ContestResult contest(AutoContestRequest request) {
        long started = System.nanoTime();
        CaseRow row = CaseRequestMapper.toCase(request);
        OffsetDateTime createdAt = OffsetDateTime.now();
        String runLabel = createdAt.toLocalDateTime().format(STAMP);
        try {
            PreparedCase prepared = prepare(row, false);
            ContestResult result;
            long nominateMillis = 0;
            if (prepared.isJudgeable()) {
                withSecondRun(prepared, createdAt);
                long nominating = System.nanoTime();
                Nomination nomination = stage.nominate(row, prepared.getCatalogue(), prepared.getPayload(),
                        prepared.getIssuer(), prepared.getLimits(), prepared.getRecords(), prepared.getSecondRun());
                nominateMillis = millisSince(nominating);
                result = conclude(request, prepared, nomination, createdAt, runLabel);
            } else {
                result = notJudgeable(request, prepared, createdAt, runLabel);
            }
            long saving = System.nanoTime();
            save(result, prepared);
            logDecided(result, prepared, millisSince(started), nominateMillis, millisSince(saving));
            return result;
```

C3. `prepare(...)`: the facts line and the time. Before:

```java
        IssuerFacts issuer = issuerFacts.read(row, reasonCodeId, includedRegions, replayOnly);
        log.info("case {}: issuer facts - {} replayed, {} extracted", row.getCaseNumber(),
                issuer.getReplayed(), issuer.getExtracted());
```

After:

```java
        long reading = System.nanoTime();
        IssuerFacts issuer = issuerFacts.read(row, reasonCodeId, includedRegions, replayOnly);
        long factsMillis = millisSince(reading);
        log.info("event=facts.read replayed={} extracted={} unanswered={} issuerDocuments={} ms={}",
                issuer.getReplayed(), issuer.getExtracted(), issuer.getNotAnswered().size(),
                row.issuerDocuments().size(), factsMillis);
```

And at the end of `prepare`, before:

```java
        return new PreparedCase(row, reasonCodeId, region, catalogue, rows, issuer, limits, records, payload, problems,
                null);
```

After:

```java
        PreparedCase prepared = new PreparedCase(row, reasonCodeId, region, catalogue, rows, issuer, limits, records,
                payload, problems, null);
        prepared.setFactsMillis(factsMillis);
        return prepared;
```

C4. `conclude(...)`: the timings and the letter failure.
- After `List<String> problems = new ArrayList<>(prepared.getProblems());` add a blank line, then
  `long lettering = System.nanoTime();`.
- The letter failure. Before:

  ```java
                log.warn("case {}: the network letter was not produced ({})", row.getCaseNumber(),
                        exception.toString());
  ```

  After:

  ```java
                log.warn("event=letter.failed error={}", exception.getClass().getSimpleName(), LogSafe.of(exception));
  ```

- After `String networkLetter = filed == null ? null : Base64.getEncoder().encodeToString(filed.getPdf());` add
  `long lettersMillis = millisSince(lettering);`.
- Around `questionnaires.generate(...)`:
  - before it: `long questioning = System.nanoTime();`;
  - after it: `long questionnaireMillis = millisSince(questioning);`.
- Around `operationsLetters.generate(...)`:
  - before it: `long summarising = System.nanoTime();`;
  - after `String opsLetter = summary.getPdf();`: `lettersMillis += millisSince(summarising);`.
- The return. Before:

  ```java
        return new ContestResult(row.getCaseNumber(), runLabel, decision.getDecision(), decision.getLetterMode(),
                record, List.copyOf(problems), response, opsLetter, filed == null ? null : filed.getPdf(),
                questionnaire.getJson(), run);
  ```

  After:

  ```java
        ContestResult result = new ContestResult(row.getCaseNumber(), runLabel, decision.getDecision(),
                decision.getLetterMode(), record, List.copyOf(problems), response, opsLetter,
                filed == null ? null : filed.getPdf(), questionnaire.getJson(), run);
        result.setLettersMillis(lettersMillis);
        result.setQuestionnaireMillis(questionnaireMillis);
        return result;
  ```

C5. `notJudgeable(...)`:
- the Javadoc becomes `/** A case with no register: UNDECIDED with the reason and no call; its run row is saved, as
  every run's is. */`;
- remove `log.warn("case {}: {}", row.getCaseNumber(), error);`.

C6. `save(...)`: after `response.setRunId(runs.recordRun(result.getRun()));` add
`LogContext.putRunId(response.getRunId());`.

C7. New private methods, after `save`:

```java
    /** The run's one line: its codes, counts and times, never a reason's, a finding's or an amount's text. */
    private static void logDecided(ContestResult result, PreparedCase prepared, long millis, long nominateMillis,
                                   long saveMillis) {
        RunRecord record = result.getRecord();
        Object reasons = record.getDecision() == null ? null : record.getDecision().get("reviewReasons");
        log.info("event=run.decided runId={} decision={} lead={} letterMode={} reviewNeeded={} reviewReasons={} "
                        + "undecidedKind={} secondRun={} problems={} findings={} ms={} factsMs={} nominateMs={} "
                        + "lettersMs={} questionnaireMs={} saveMs={}",
                orDash(result.getResponse().getRunId()), result.getDecision(),
                orDash(result.getRun() == null ? null : result.getRun().getLeadCode()), result.getLetterMode(),
                record.getReviewNeeded(), reasons instanceof List ? ((List<?>) reasons).size() : 0,
                record.getUndecidedReason() == null ? "-" : record.getUndecidedReason().getKind(),
                prepared.getSecondRun() != null, result.getProblems().size(),
                record.getLetterFindings() == null ? 0 : record.getLetterFindings().size(), millis,
                prepared.getFactsMillis(), nominateMillis, result.getLettersMillis(), result.getQuestionnaireMillis(),
                saveMillis);
    }

    private static Object orDash(Object value) {
        return value == null ? "-" : value;
    }

    private static long millisSince(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
```

C8. `pretty(...)`. Before:

```java
            throw new IllegalStateException("could not write the run record: " + exception.getMessage(), exception);
```

After:

```java
            throw ServiceStateException.appending("could not write the run record", exception.getMessage(), exception);
```

C9. `recordFailure(...)`. Before:

```java
            log.warn("case {}: the failed run could not be recorded ({})", row.getCaseNumber(), recording.toString());
```

After:

```java
            log.warn("event=run.record.failed error={}", recording.getClass().getSimpleName(), LogSafe.of(recording));
```

`PreparedCase` (`setFactsMillis`, `getFactsMillis`) and `ContestResult` (`setLettersMillis`,
`setQuestionnaireMillis`) are in `copy/`.

## 4. `LlmClient`, the office's own client (against `docs/office/LlmClient.java`)

The office's class is a `@Component` with its own JDK client built by `RestClient.builder()`. It makes no converter
lookup of its own, so **the bridge needs nothing from it**, provided Jackson 3 is off the classpath (§8 check 1).

- **Today:** the office's classpath holds Jackson 3.1.5 (measured from its `pom.xml`), so `RestClient.builder()` picks
  Jackson 3's converter, and the `/facts/extract` and `/scenario/nominate` bodies are written by Jackson 3.
- **After the port:** they are written by Jackson 2. Every request class the orchestrator writes to the llm fixes its
  property order (`@JsonPropertyOrder`), so the order is the same under both.
- **The check:** one case's run after deploying (the report's §4 step 3).

**L1. The imports.** Add:

```java
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;
```

**L2. The client, timed** (the constructor, office lines 44 to 47). Before:

```java
        this.restClient = RestClient.builder()
                .baseUrl(applicationProps.getLlmService().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
```

After:

```java
        this.restClient = RestClient.builder()
                .baseUrl(applicationProps.getLlmService().getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(LlmClient::timed)
                .build();
```

**L3. The line per call.** Add these two methods after the constructor. This is the POC's `LlmClientImpl.timed`,
unchanged.

```java
    /** One line per llm call: its fixed path, its status and its time; a call that reaches no llm names the class. */
    private static ClientHttpResponse timed(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long started = System.nanoTime();
        String path = request.getURI().getPath();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int status = response.getStatusCode().value();
            if (status < 400) {
                log.info("event=downstream.call target=llm path={} status={} ms={}", path, status, millisSince(started));
            } else {
                log.warn("event=downstream.call target=llm path={} status={} ms={}", path, status, millisSince(started));
            }
            return response;
        } catch (IOException exception) {
            log.warn("event=downstream.call target=llm path={} status=- ms={} error={}", path, millisSince(started),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private static long millisSince(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
```

**L4. Not JSON** (`nominate`, office line 78). The text stays exactly as it is, since `UndecidedKinds` reads its
prefix. Before:

```java
            throw new IllegalStateException("the nominate answer is not JSON: " + exception.getMessage(), exception);
```

After:

```java
            throw ServiceStateException.appending("the nominate answer is not JSON", exception.getMessage(), exception);
```

The refusals already raise `LlmCallFailed` (copied whole; its log text is the status and the errors' count and
fields). Nothing else changes: the headers and the bearer token are as they are.

## 5. `restclient/RestInvoker` (against `docs/office/RestInvoker.java`)

**R1. Which methods are still called.** Since fix-issues stage 2 the scheduler files, so the questionnaire save, the
names update, the desk move and the letter upload are no longer the orchestrator's. In the office's `src/main`:

```
grep -rn "restInvoker\.\|RestInvoker" src/main/java --include=*.java | grep -v "restclient/RestInvoker.java"
```

**Delete each method nothing calls.** Expected: `getDocumentBase64` (the office's `DocumentService` for
`ExhibitPages`), and possibly `getAllDocuments`, stay; `postVisaQuestionarieCollabRepresentment`,
`updateVisaQuestionarieCollabRepresentment`, `getQuestionnaire`, `uploadChargebackLetterWithCase`,
`updateQuestionnaireDocument` and `updatDesk` go. For each method that stays, apply R3 and R4.

**R2. The token** (office lines 134 and 140). Remove both:

```java
        log.info("Fetching the auth token from security context BEFORE: " + token);
```

```java
        //log.info("Fetched the auth token from security context AFTER: " + token);
```

**R3. The lines that print a request object** (office lines 86, 105, 151, 181 and 199): only in a method that
stays. Each becomes named fields, never the object:

| office line | before | after |
|---|---|---|
| 86 | `log.info("Post Visa Collaboaration questionarie endpoint : Executing rest service POST Call {} ", visaCollaboarationReprRequest);` | `log.info("event=questionnaire.save method=POST caseNumber={} actionSequence={}", caseNumber, actionSequence);` |
| 105 | `log.info("Post Visa Collaboration questionnaire update endpoint : Executing rest service PUT Call {} ", visaCollaboarationReprRequest);` | `log.info("event=questionnaire.save method=PUT caseNumber={} actionSequence={}", caseNumber, actionSequence);` |
| 151 | `log.info("Uploading document to URL: {} with request body: {}", docUploadUri, reqBody);` | `log.info("event=document.upload caseNumber={} actionSequence={} bytes={}", documentRequest.getCaseNumber(), documentRequest.getActionSequence(), docReqBody.length);` (use the office's own getter names on `UploadDocumentRequest`) |
| 181 | `log.info("Update document names in questionarie endpoint : Executing rest service POST Call {} ", updateQuestionnaireDocumentRequest);` | `log.info("event=document.names documents={}", updateQuestionnaireDocumentRequest.getDocuments() == null ? 0 : updateQuestionnaireDocumentRequest.getDocuments().size());` |
| 199 | `log.info("Update desk : Executing rest service POST Call {} ", updateDeskRequest);` | `log.info("event=desk.move desk={}", updateDeskRequest.getDeskNumber());` (the office's getter name on `UpdateDeskRequest`) |

**R4. The eight `ExceptionUtils.getStackTrace(e)` lines** (office lines 73, 92, 111, 127, 156, 169, 187, 205): only in
a method that stays.
- **Each method rethrows,** and the caller handles the failure. For `getDocumentBase64` that is `ExhibitPages`
  (§2 E7), which logs the clean trace. So by A11 this line carries **no trace**: a layer that only passes the
  exception on logs no second trace.
- **The forbidden part goes either way.** `ExceptionUtils.getStackTrace` printed the third party's message, which for
  a `RestClientResponseException` holds the response body.

Before (each):

```java
            log.error("exception while processing request. Error message : {} ", ExceptionUtils.getStackTrace(e));
            throw e;
```

After:

```java
            log.warn("event=<call>.failed error={}", e.getClass().getSimpleName());
            throw e;
```

with `<call>` per method: `documents.get` (`getAllDocuments`), `questionnaire.save`, `questionnaire.update`,
`questionnaire.get`, `document.upload`, `document.base64` (`getDocumentBase64`), `document.names`, `desk.move`.

Then remove `import org.apache.commons.lang3.exception.ExceptionUtils;`.

**R5. Leave line 67** (`Executing rest service GET {}` with the URL) and line 121 as they are. They print no body, no
token and no document text.

## 6. `service/RestInvokerQuestionnaireTarget` and the old `ClassifierContest` (against `docs/office/`)

- **`RestInvokerQuestionnaireTarget`:** the fix-issues stage 2 port deleted it, together with `QuestionnaireTarget`.
  If it is still in the office tree, delete it now; nothing calls it since the scheduler files. If it must stay,
  office line 49 `log.info("Update questionnaire document request: {}", request);` becomes
  `log.info("event=document.names documents={}", documentNames.size());`.
- **`service/ClassifierContest`:** if the office tree still has the class from before CL-3041 (the interface's
  implementation in one file, `docs/office/ClassifierContest.java`), it goes, as `ClassifierContestImpl` replaces it.
  If it must stay, its line 148 prints `documents=<n>`, never the names.

## 7. `logback-spring.xml` (the console pattern; no office copy is in hand)

This is the branch's `application.yml` change (`logging.pattern.level`). In the office, the console appender's
pattern carries it.
- **In each console `<pattern>`** (both lines, if there are two), the level slot becomes
  `%5p [corr=%X{correlationId:--} case=%X{caseKey:--}]`.
- **Where the pattern already has `[corr=%X{correlationId:--}]`:** before `%5p [corr=%X{correlationId:--}]`, after
  `%5p [corr=%X{correlationId:--} case=%X{caseKey:--}]`.
- **Where it has no slot:** insert ` [corr=%X{correlationId:--} case=%X{caseKey:--}]` directly after the level
  conversion (`%5p`, `%-5level` or `%level`).

**Nothing else in the file changes.** The `LogstashTcpSocketAppender` and the `LogstashEncoder` stay as they are:
encoder 8.1 keeps both classes, and the encoder already sends every MDC key (`correlationId`, `platform`,
`caseNumber`, `actionSequence`, `caseKey`, `runId`) as its own field.

**One check:** `org.springframework.web.client` must stay above DEBUG. At DEBUG it prints the llm request bodies. The
office file holds `org.springframework` at `error`, which is enough.

## 8. The checks before building

1. **No Jackson 3:** `mvn dependency:tree -Dscope=test | grep tools.jackson` must print nothing. The `pom.xml` in
   this package resolves with none (measured). The office's current file resolves `tools.jackson.core` 3.1.5 through
   `spring-boot-starter-web` and encoder 9.0.
2. **openpdf:** `grep -rlE "import (com\.lowagie|org\.openpdf)\." src/main`. If nothing is found, remove the `openpdf`
   dependency from `pom.xml`.
3. **httpclient 4:** `grep -rl "import org.apache.http\." src/main`. If nothing is found, remove the `httpclient`
   dependency.
4. **commons-lang3:** after R4, `grep -rl "import org.apache.commons.lang3" src/main`. The office declares no
   `commons-lang3` dependency; it arrives through springdoc (3.20.0 in the "after" tree). If office code still imports
   it, add the dependency with no version.
5. **springdoc-openapi** 2.8.5 is left as it is, named for the Snyk scan. The 3.x line is the Boot 4 line. If it is
   ever moved, it may bring `spring-boot-starter-jackson`, which then takes the same exclusion.
6. **Settings:** the `.enabled` values are `true` or `false`, and the profile names hold only letters, digits, `-`,
   `_`, `.`, `+` and `@`.
7. **`logstash-logback-encoder` 8.1** is in the office's Maven repository.

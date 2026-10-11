# Platform Support for Visa & Mastercard Queue Batches

Oct 10, 2026 · @Rama Krishna Singidi

## Overview

All three queue batches must read their network queue once per platform (CORE, PIN, LATAM, NAP), because each platform has its own Visa / Mastercard credentials held by the adapter. Each job reads one platform's queue, processes those items, then moves to the next platform, and passes that platform on every adapter call made for an item.

| Job | Network | Queue read today | Shape of change |
| --- | --- | --- | --- |
| gcp-visa-disputes-processor-batch | Visa (RTSI) | List of queues, fetched one queue at a time | Reader walks platform × queue pairs |
| wdp-mcm-first-chargeback-queue-batch | Mastercard | One queue (unworked chargebacks), all claims up front | Reader walks platforms, one platform's claims at a time |
| wdp-mcm-receiver-case-filling-queue-batch | Mastercard | One queue (receiver case filing), all claims up front | Same as first-chargeback, plus its filters |

The Spring Batch job/step config, chunk size and scheduler cron do not change in any of the three jobs. Only the reader, the item model, the adapter-calling services and the hardcoded CORE values change.

Run time grows with the number of platforms because each job stays sequential. Check current run duration in `BATCH_JOB_EXECUTION` before rollout; if 4× no longer fits the cron interval, that is the trigger to move Visa to the scheduler project.

## Common changes (all three jobs)

Every job gets the same five changes; the job sections below only list where they land.

1. **Platform list in config.** Add `platformList: ${platform_list}` under `app.batchProperties` in `application.yml` and a `List<String> platformList` in `ApplicationProps.BatchProperties`. Env value: `CORE,PIN,LATAM,NAP`. A platform can be switched on or off per environment without a deploy.
2. **Platform on the reader item.** The item the reader returns (`QueueReaderItem` for Visa, `QueueClaim` for Mastercard) gets a `platform` field. The reader sets it; it is not read from the adapter's JSON.
3. **Platform on every adapter call.** The queue read, every detail/search call in the processor, and the remove/acknowledge call in the writer all send the item's platform, so the adapter picks the right credentials. Reading under one platform and acknowledging under another leaves the item in the queue.
4. **Replace hardcoded CORE.** Outbox `platform` (`c_acq_platform`) and event fields that are hardcoded to `CORE` take the item's platform instead; otherwise LATAM, NAP and PIN cases are stored as CORE.
5. **One platform failing must not stop the others.** Wrap each platform's queue read in its own try/catch, log `platform` + queue at error level, and move to the next platform.

Read order inside a job is one platform at a time: fetch platform A's queue, hand out its items one by one, then fetch platform B. Only one platform's (and for Visa, one queue's) items are in memory at a time, and each queue is read just before it is processed.

## Visa: gcp-visa-disputes-processor-batch

The reader's queue-by-queue logic stays; `nextQueueSeq` now walks a list of platform + queue pairs instead of queue names.

### How the reader works today

- `@BeforeStep` (`BatchItemReader` lines 41–48) loads `queueNameList` (names only) and fetches the first non-empty queue.
- `retrieveQueueItems()` (lines 50–73) fetches all pages of the queue at `nextQueueSeq`; an empty or failed queue moves to the next index.
- `read()` (lines 76–98) hands out one item per call; when the current queue is exhausted (line 87) it fetches the next queue; it returns `null` when no queues remain.

### BatchItemReader changes

1. New holder class (or a `String[]`):

```java
@Data @AllArgsConstructor
public class PlatformQueue {
    private String platform;
    private String queueName;
}
```

2. New fields after line 39: `private String platform;` and `private List<PlatformQueue> platformQueueList;`
3. `@BeforeStep`, lines 45–46: build the pairs once, then size from the pairs.

```java
List<String> platformList = props.getBatchProperties().getPlatformList();
queueNameList = props.getBatchProperties().getQueueNameList();
platformQueueList = new ArrayList<>();
for (String p : platformList) {
    for (String q : queueNameList) {
        platformQueueList.add(new PlatformQueue(p, q));
    }
}
queueListSize = platformQueueList.size();
```

After `@BeforeStep` with CORE, PIN and FC, PREARB the list holds `CORE-FC, CORE-PREARB, PIN-FC, PIN-PREARB` (names only, no cases).

4. `setQueueDetails` (lines 100–103): take both values from the pair.

```java
PlatformQueue pq = platformQueueList.get(nextQueueSeq);
platform  = pq.getPlatform();
queueName = pq.getQueueName();
```

5. Line 56: `visaRTSIService.getBatchQueueItems(queueName, platform);`
6. Line 86: add `readerItem.setPlatform(platform);`
7. Catch block (lines 66–71): add `log.error` with platform + queue before moving on.
8. No change to the empty-queue skip or the next-queue switch (lines 59–71, 87–92).

### Model and service changes

- `QueueReaderItem`: add `platform`.
- `VisaRTSIService` / `VisaRTSIServiceImpl`: `getBatchQueueItems(queueName, platform)` (line 67) and its paging overload (line 85) pass platform into `SIGetBatchQueueRequest.instance(queueName, pageNum, platform)` (line 91).
- `getHyperSearchDetail` (163), `markBatchQueueItemAsRead` (190) and `getDisputeDetail` (216) already take `platform` but only use it in logs; put it on the request (lines 168, 197, 228 and the pre-arb response call).
- `searchVisaTransaction` (270) has no platform parameter; add one.
- Where it goes on the wire: a field on `RequestHeader` (request body) or an HTTP header in `RestInvoker.getHttpHeaders` — one place, decided by the Visa adapter contract.

### BatchItemProcessor changes

- Line 60 derives platform from `networkId` (`VISA` → CORE, else PIN). Replace with `queueReaderItem.getPlatform()`.
- Lines 101, 202: pass the item's platform to hyper search and transaction search.
- `isValidPinTransaction` (lines 197–222) re-sets platform to PIN or CORE from `cardholderIdMethodCode`. Decide whether this check stays once the PIN queue is read with PIN credentials (open question below).

### BatchItemWriter

- Lines 123 and 130 already pass `item.getPlatform()` to `markBatchQueueItemAsRead`; no change beyond the service putting it on the request.

## Mastercard: wdp-mcm-first-chargeback-queue-batch

This job reads a single queue, so the reader gets a platform loop: fetch one platform's claims, hand them out, then fetch the next platform.

### How the reader works today

- `@BeforeStep` (`BatchItemReader` lines 37–44) makes one call, `mcmService.getUnworkedChargebackClaims()`, and loads every claim into `queueClaims`.
- `read()` (lines 47–56) hands out one `QueueClaim` (only `claimId`) per call.
- `DataPowerRestInvoker.getHttpHeaders()` (lines 76–83) sends one fixed credential, `vantiveLicense`, on every call.

### BatchItemReader changes

1. Fields (replace lines 34–35):

```java
private List<QueueClaim> queueClaims;
private int index;
private List<String> platformList;
private int platformSeq;
private String platform;
```

2. `@BeforeStep`:

```java
platformList = props.getBatchProperties().getPlatformList();
platformSeq = 0;
loadNextPlatform();
```

3. New `loadNextPlatform()` — skips empty or failed platforms until one has claims or none remain:

```java
private void loadNextPlatform() {
    queueClaims = null;
    index = 0;
    while (platformSeq < platformList.size()) {
        platform = platformList.get(platformSeq);
        platformSeq++;
        queueClaims = getUnworkedChargebackClaims(platform);
        if (CollectionUtils.isNotEmpty(queueClaims)) {
            log.info("platform {} claims size {}", platform, queueClaims.size());
            return;
        }
        log.info("no claims for platform {}, moving to next", platform);
    }
}
```

4. `read()`:

```java
if (queueClaims != null && index == queueClaims.size()) {
    loadNextPlatform();
}
if (queueClaims != null && index < queueClaims.size()) {
    QueueClaim claim = queueClaims.get(index++);
    claim.setPlatform(platform);
    return claim;
}
return null;
```

5. `getUnworkedChargebackClaims` (lines 58–69) takes `platform`, passes it to the service, and keeps `filterQueueClaims`. Its catch logs the platform and returns null, so the loop moves on.

### Model and service changes

- `QueueClaim`: add `platform`. `ProcessedItem`: add `platform`.
- `MCMService` / `MCMServiceImpl`: add `platform` to `getUnworkedChargebackClaims` (line 37), `getClaimDetail` (83), `getSettledTransactionDetail` (113) and `acknowledgeChargebackWorked` (138).
- `DataPowerRestInvoker`: `get` and `put` take `platform`; `getHttpHeaders(platform)` adds it (header name per Mastercard adapter contract).

### Processor, util and writer

- `BatchItemProcessor` line 54: `getClaimDetail(claimId, queueClaim.getPlatform())`; line 177 settled-transaction call also passes it; set `ProcessedItem.platform`.
- `ProcessorUtil` line 49 (outbox `platform`) and line 85 (event `sourceSystem`): replace `ApplicationConstants.CORE` with the item's platform.
- `BatchItemWriter` line 88: `acknowledgeChargebackWorked(acknowledgeList, platform)`. Safe with chunk size 1; if chunk grows, group `acknowledgeList` by platform and acknowledge per group.

## Mastercard: wdp-mcm-receiver-case-filling-queue-batch

Same reader change as first-chargeback (`platformList`, `platformSeq`, `loadNextPlatform()`, new `read()`), with five differences listed here. Package is `com.wp.wdp.mcm.prearb.arb.queue.batch`.

### Differences from first-chargeback

1. **Queue read is a POST.** `McmServiceImpl` line 101 posts a request body to `receiver_case_filing_queue_url`. `getSenderAndReceiverCaseFilingClaims(platform)` sends the platform on that POST (header or body field, per adapter contract).
2. **Reader filters stay per platform.** `getSenderAndReceiverCaseFilingClaims()` (`BatchItemReader` lines 47–64) runs `filterQueueClaims` then `validateQueueClaims` (last-modified user and date). Call both on each platform's list inside `loadNextPlatform()`.
3. **Null guard needed.** `validateQueueClaims` line 67 calls `claimList.size()` with no null check. Today an empty queue throws there and the outer catch hides it; with the platform loop it would fire for every empty platform. Add:

```java
if (CollectionUtils.isEmpty(claimList)) {
    return Collections.emptyList();
}
```

4. **More adapter calls in the processor.** `getClaimDetail` at `BatchItemProcessor` lines 70 and 361 (second one for the standard claim) and `getSettledTransactionDetail` at line 327 all take `queueClaim.getPlatform()`.
5. **No writer change.** This job never calls Mastercard to remove an item; the last-modified filter keeps items from being reprocessed.

### Same as first-chargeback

- `QueueClaim` and `ProcessedItem` get `platform`.
- `DataPowerRestInvoker.getHttpHeaders()` (line 83, fixed `vantiveLicense`) takes `platform`; pass it through `get` and `post`.
- `ProcessorUtil` line 59 (outbox `platform`) and line 139 (event `sourceSystem`): replace hardcoded `CORE` with the item's platform.

## Open questions, risks and rollout

### Open questions

- [ ] Visa adapter: is platform sent as a `RequestHeader` field in the body or as an HTTP header? Exact name and allowed values.
- [ ] Mastercard adapter: header, query parameter or body field? The four URLs are env vars, so a query parameter is also possible.
- [ ] Does each platform's queue return only that platform's cases? If yes, the Visa `networkId` derivation and `isValidPinTransaction` PIN/CORE re-tagging can go; if not, some filtering stays.
- [ ] Visa `filterVisaDisputes` filters on `visaDisputeNetworkIds`; do LATAM and NAP use different network IDs (per-platform setting)?
- [ ] Mastercard event `sourceSystem`: platform value, or stay CORE?
- [ ] Do the rules service, case management and outbox consumers accept LATAM and NAP?

### Risks

- **Run time:** each job stays sequential, so 4 platforms means roughly 4× the Visa / Mastercard calls per run. Check `BATCH_JOB_EXECUTION` durations against the cron interval.
- **Wrong-credential acknowledge:** if any remove/acknowledge call misses the platform, the item stays in that platform's queue and is reprocessed next run.
- **Silent platform failure:** a bad credential for one platform only shows in logs; add an error log with platform + queue and alert on it.

### Rollout checklist

- [ ] Set `platform_list=CORE` first in each environment; behaviour should match today.
- [ ] Add PIN, then LATAM and NAP one at a time in cert; verify reads, outbox `c_acq_platform`, and queue removal per platform.
- [ ] Confirm run duration still fits the cron interval with all four platforms.
- [ ] Same steps in prod, one platform at a time.
- [ ] Later: copy the shared-class changes into `gcp-visa-disputes-processor-scheduler`, where the platform loop wraps `QueueProcessingService.processQueue`.

# Visa 13.1, issuer documents only: four synthetic NAP cases for office dev

Each case has one issuer PDF and no merchant pack. All four contested in the POC on 2026-10-20.

| Case | Argument | Issuer PDF says | POC result |
|---|---|---|---|
| POCVISA13S06 | G15 fraud | "did not make this purchase and do not recognise the merchant" | CONTEST_STRONG, G15 |
| POCVISA13S6A | G15 fraud | the same, AliExpress | CONTEST_STRONG, G15 |
| POCVISA13S7A | G16 quality | goods received, shade cracked, wrong size | CONTEST_STRONG, G16 |
| POCVISA13S03 | G13 cancelled | "Did the cardholder cancel prior to the expected date? Yes" | CONTEST_MODERATE, G13 (STRONG on 2026-10-06) |

## Steps (dev)
1. Run `1-dev-case-action.sql`. It adds `nap."case"` and `nap.action` '01' for the four cases.
2. In dev's document service, upload each PDF in `documents/` to its case (the case number is the file's prefix)
   with document type **ISSRDOC**. `CaseServiceImpl` reads ISSRDOC as the issuer's side. RESPDOC or MISCDOC would
   make it a merchant document.
3. Run `2-dev-queue-runs.sql`. It queues one RUN row per case in `wdp.ai_response_events` (REVIEW_MODE). The
   scheduler picks them up on its next tick.
4. Check the results with the queries at the end of `2-dev-queue-runs.sql`.

## Notes
- S6A and S7A share the network case number and ARN; they are variants of the same base case.
- Dates are 2025. Nothing checks the response-due date, which is NULL.
- Clean-up statements are at the end of `1-dev-case-action.sql`.

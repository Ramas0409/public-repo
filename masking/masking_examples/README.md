# Masking test set – card data and PII

Twelve synthetic dispute cases, two per reason code, for the card-data and PII masking module of `claude/WDP-PCI-PII-MASKING-SOLUTION-DESIGN.md` (draft 0.1). They test the masking, not the decision: every row of `masking_expectations.xlsx` is a value printed on a page that the masker must replace, tokenize or leave alone, with the expected result under Part 3 and whether v1 covers it under Part 6. Every person, company, bank, address, ID and number is invented; card numbers are public test numbers.

## Files

- Per case, named by `networkCaseNumber`: `_Issuer_1.pdf` (the dispute form), `_Issuer_2.pdf` (the cardholder's statement), `_Merchant.pdf` (the merchant's pack).
- `cases.xlsx`: one row per case with every request field except `documents`, and what the case covers.
- `masking_expectations.xlsx`: sheet `items` (one row per value), sheet `groups` (token groups, and the sets that must keep different tokens), sheet `legend`.

## Cases

| case | networkCaseNumber | code | platform | languages | pages (I1/I2/M) | scanned / image pages | items | covers |
|---|---|---|---|---|---|---|---|---|
| POCVISA131M01 | 2716408395 | 13.1 | NAP | English, Spanish | 1/1/5 | – | 138 | 13.1 in Spanish and English: cardholder name in full, with and without accents, in capitals surname first, a Spanish double surname, the household member who signed, C/ and Avda. abbreviations against the full addresses, a plus-tagged webmail address, phones with spaces and dashes, DNI, date of birth, GPS on the proof of delivery, DHL waybill. |
| POCVISA131M02 | 3150927468 | 13.1 | NAP | English, French | 1/1/6 | – | 159 | 13.1 in English and French: a merchant trading under her own name with merchant.name filled (her name kept, her initial form and first name tokenized), a phone order form with French card, expiry and CVV labels, UPS 1Z tracking, a delivery code, GPS and a signed POD, other customers' names in a shipping log. |
| POCVISA137M03 | 4402815937 | 13.7 | NAP | English, German | 1/1/5 | – | 130 | 13.7 in German: a registration form in two columns (an address split across lines), German card, expiry and CVV labels, an umlaut name and its ASCII spelling, the son with the same surname, an IBAN with spaces beside its BIC, a cancellation policy with a 3-digit number near a CVC mention. |
| POCVISA137M04 | 5093371826 | 13.7 | CORE | English | 1/1/6 | – | 134 | 13.7 in English (US): St against Street, phones in three US formats, a misread card number next to a card label, a 13-digit Luhn-valid order number, a refund record with a routing and account number, a 3-digit number near 'security code' in the policy. |
| POCVISA125M05 | 6128450937 | 12.5 | NAP | Catalan, English, Spanish | 1/1/7 | Merchant p4, Merchant p3 (image under heading) | 151 | 12.5 in Spanish and Catalan: a hotel folio printing the card twice in two formats and a Mastercard deposit, a card authorisation form with Spanish labels, a ledger screenshot under a text heading, a scanned registration card with NIE, date of birth and card, a Catalan receipt, a room number near 'código de seguridad'. |
| POCVISA125M06 | 6893017245 | 12.5 | CORE | English | 1/1/6 | Merchant p5 | 134 | 12.5 in English (US): a close-out folio printing the card twice (dashes, unbroken) and an Amex hold, an authorisation form with an Amex 4-digit code, a POS log with a track 2 line, a staff note with card, expiry and CVV in a row, a scanned screen print of the customer ledger, a driver's licence number. |
| POCVISA132M07 | 7246019583 | 13.2 | NAP | English | 1/1/6 | – | 105 | 13.2 in English (UK): a transaction ID that looks like an Amex number, billing records with a Mastercard 2-series number and MM/YY-style dates, a login history with IPv4, IPv6, a 192.168 address, an app version like an IP and a device ID, a chat with a sort code and account number, a username, a temporary password, a one-time code, a reset link with a token, a Royal Mail number. |
| POCVISA132M08 | 7781250364 | 13.2 | NAP | English, French | 1/1/4 | – | 98 | 13.2 in French: a hyphenated surname, phones in French national and international form, an email written as 'name (at) domain', a one-time code, an IBAN with spaces and its BIC in a chat, IPv4, IPv6, an Android device ID, a device fingerprint, an app version like an IP. |
| POCVISA133M09 | 8350641927 | 13.3 | NAP | English, German | 1/1/7 | Merchant p7 | 140 | 13.3 in German: returns emails, a web chat, a damage report, a workshop invoice with a 'Prüfnummer' that is not a CVV, a 19-digit and a 12-digit Maestro, a scanned workshop intake form with a Maestro card, a German expiry label and the spouse with the same surname, a DHL Paket number. |
| POCVISA133M10 | 8812739046 | 13.3 | NAP | English, French | 1/1/6 | – | 133 | 13.3 in English and French: the same person-named merchant as M02 with merchant.name left blank (her name tokenized), returns emails, a chat export, a carrier damage report with a 16-digit Luhn-valid tracking number, a Royal Mail number, a parcel locker, a restorer's invoice with a UK sort code and account number. |
| POCVISA104M11 | 9135027846 | 10.4 | CORE | English | 1/1/5 | – | 122 | 10.4 in English (US): a gateway log with AVS, CVV2, ECI and CAVV and a 3-digit number after 'CVV2', device records with an iOS device ID, an IMEI and a fingerprint, IPv4 and IPv6, two earlier undisputed orders with the same identifiers, a prior-order export, a Discover number in a table cell, FedEx numbers, a phone with dots. |
| POCVISA104M12 | 9604318572 | 10.4 | NAP | English | 1/1/5 | Merchant p5 | 113 | 10.4 in English (UK): click and collect with a scanned collection slip and passport copy (MRZ, passport number, date of birth, card presented), a National Insurance number, a gateway log, an Android ID and fingerprint, two earlier undisputed orders, a prior-order export. |

`merchant.name` is filled on every case except POCVISA133M10, where the merchant trading under her own name (Hélène Marchetti) is the same as in POCVISA131M02, which carries her name.

## Counts

1557 rows: PII 497, card data 70, look-alike 29, must keep 961. 54 rows are marked not covered by v1.

### By type and language

| category | type | English | Spanish | German | French | Catalan | total | expected |
|---|---|---|---|---|---|---|---|---|
| card data | CVV directly after card number and expiry | 1 |  |  |  |  | 1 | [CVV] |
| card data | CVV next to a label | 1 | 1 | 1 | 1 |  | 4 | [CVV] |
| card data | card number | 11 | 8 | 3 | 1 |  | 23 | [CARD ending nnnn] |
| card data | card number, 12-digit Maestro |  |  | 1 |  |  | 1 | [CARD ending nnnn] |
| card data | card number, last four only | 7 | 3 | 4 | 3 | 1 | 18 | keep |
| card data | card number, one digit misread (Luhn fails) | 1 |  |  |  |  | 1 | [CARD ending nnnn] |
| card data | card number, partly masked | 15 |  |  |  |  | 15 | [CARD ending nnnn] |
| card data | expiry directly after a card number | 1 |  |  |  |  | 1 | [EXPIRY] |
| card data | expiry next to a label | 1 | 1 | 2 | 1 |  | 5 | [EXPIRY] |
| card data | magnetic-stripe track 2 data (after the card number) | 1 |  |  |  |  | 1 | mask (no v1 replacement) |
| PII | GPS coordinates | 2 |  |  |  |  | 2 | mask (no v1 replacement) |
| PII | IBAN |  |  | 2 | 1 |  | 3 | [INTERNATIONAL_BANK_ACCOUNT_NUMBER_xxxx] |
| PII | IP address | 11 |  |  | 2 |  | 13 | [IP_ADDRESS_xxxx] |
| PII | Spanish DNI |  | 1 |  |  |  | 1 | mask (no v1 replacement) |
| PII | Spanish NIE |  | 2 |  |  |  | 2 | mask (no v1 replacement) |
| PII | UK National Insurance number | 1 |  |  |  |  | 1 | [UK_NATIONAL_INSURANCE_NUMBER_xxxx] |
| PII | UK sort code | 2 |  |  |  |  | 2 | keep |
| PII | US routing number | 1 |  |  |  |  | 1 | keep |
| PII | bank account number | 3 |  |  |  |  | 3 | [US_BANK_ACCOUNT_NUMBER_xxxx] |
| PII | date of birth next to a label | 2 | 3 | 1 |  |  | 6 | mask (no v1 replacement) |
| PII | device ID | 4 |  |  | 1 |  | 5 | mask (no v1 replacement) |
| PII | device fingerprint | 2 |  |  | 1 |  | 3 | mask (no v1 replacement) |
| PII | driver's licence number | 2 |  |  |  |  | 2 | [DRIVER_ID_xxxx] |
| PII | email address | 55 | 12 | 18 | 7 | 1 | 93 | [EMAIL_xxxx] |
| PII | one-time code | 2 |  |  | 1 |  | 3 | [CODE] |
| PII | passport MRZ line | 2 |  |  |  |  | 2 | mask (no v1 replacement) |
| PII | passport number (non-US) | 1 | 1 |  |  |  | 2 | mask (no v1 replacement) |
| PII | password next to a label | 1 |  |  |  |  | 1 | [PASSWORD] |
| PII | password-reset token in a URL query string | 1 |  |  |  |  | 1 | mask (no v1 replacement) |
| PII | person name | 104 | 26 | 32 | 17 | 2 | 181 | [NAME_xxxx] |
| PII | person name = merchant.name (exempt) | 3 |  |  | 1 |  | 4 | keep |
| PII | phone number | 52 | 13 | 14 | 9 | 2 | 90 | [PHONE_xxxx] |
| PII | street address | 37 | 12 | 15 | 7 | 2 | 73 | [ADDRESS_xxxx] |
| PII | username | 2 | 1 |  |  |  | 3 | [USERNAME_xxxx] |
| look-alike | 16-digit tracking number, Luhn-valid | 1 |  |  | 1 |  | 2 | keep |
| look-alike | 3-digit number directly after a CVV-like label | 1 |  | 1 |  |  | 2 | keep |
| look-alike | 3-digit number near a CVV-like word | 2 | 1 | 3 |  |  | 6 | keep |
| look-alike | MM/YY-style date, no expiry label | 4 |  |  |  |  | 4 | keep |
| look-alike | month and year, no expiry label | 3 |  | 2 |  |  | 5 | keep |
| look-alike | order number, 13 digits, Luhn-valid | 5 |  |  |  |  | 5 | keep |
| look-alike | software version that looks like an IPv4 address | 2 |  |  | 2 |  | 4 | keep |
| look-alike | transaction ID, 15 digits, Amex prefix, Luhn-valid | 1 |  |  |  |  | 1 | keep |
| must keep | ARN (23 digits) | 19 | 2 | 2 | 1 |  | 24 | keep |
| must keep | AVS / CVV2 / ECI result code | 8 | 2 |  | 1 |  | 11 | keep |
| must keep | BIC |  |  | 2 | 1 |  | 3 | keep |
| must keep | CAVV (3-D Secure value) | 2 |  |  |  |  | 2 | keep |
| must keep | URL | 12 | 4 | 3 | 1 |  | 20 | keep |
| must keep | amount | 67 | 25 | 20 | 12 | 1 | 125 | keep |
| must keep | authorization code | 21 | 3 | 2 | 2 |  | 28 | keep |
| must keep | carrier name | 13 | 2 | 5 | 4 |  | 24 | keep |
| must keep | case / reference number | 47 | 3 | 7 | 2 |  | 59 | keep |
| must keep | city | 29 | 4 | 5 | 2 |  | 40 | keep |
| must keep | company name | 57 | 15 | 18 | 4 | 1 | 95 | keep |
| must keep | company registration or tax number | 1 | 2 | 1 |  |  | 4 | keep |
| must keep | country | 2 | 2 |  | 2 |  | 6 | keep |
| must keep | date | 145 | 30 | 29 | 23 | 1 | 228 | keep |
| must keep | date and time | 49 | 8 | 7 | 4 |  | 68 | keep |
| must keep | last four digits | 10 | 2 |  |  |  | 12 | keep |
| must keep | merchant descriptor | 20 | 2 | 2 | 1 |  | 25 | keep |
| must keep | order / booking number | 39 | 6 | 7 | 8 | 1 | 61 | keep |
| must keep | postcode | 1 |  |  |  |  | 1 | keep |
| must keep | postcode and city | 38 | 10 | 14 | 10 | 2 | 74 | keep |
| must keep | retrieval reference number | 12 |  |  |  |  | 12 | keep |
| must keep | tracking number | 16 | 2 | 4 | 4 |  | 26 | keep |
| must keep | transaction ID | 12 |  |  |  |  | 12 | keep |
| must keep | vehicle registration plate (rental fleet car) | 1 |  |  |  |  | 1 | keep |

### By read path

| read path | card data | PII | look-alike | must keep |
|---|---|---|---|---|
| digital | 62 | 465 | 29 | 933 |
| scanned | 8 | 32 | 0 | 28 |

### Must-keep and look-alike values by case

Every case lists the must-keep values its own documents carry. The format-specific ones sit where the document type brings them, as the table shows.

| category | type | M01 | M02 | M03 | M04 | M05 | M06 | M07 | M08 | M09 | M10 | M11 | M12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| look-alike | 16-digit tracking number, Luhn-valid |  |  |  |  |  |  |  |  |  | 2 |  |  |
| look-alike | 3-digit number directly after a CVV-like label |  |  |  |  |  |  |  |  | 1 |  | 1 |  |
| look-alike | 3-digit number near a CVV-like word |  |  | 2 | 1 | 1 |  | 1 |  | 1 |  |  |  |
| look-alike | MM/YY-style date, no expiry label |  |  |  | 1 |  |  | 2 |  |  | 1 |  |  |
| look-alike | month and year, no expiry label |  |  | 2 | 1 |  |  | 1 |  |  |  |  | 1 |
| look-alike | order number, 13 digits, Luhn-valid |  |  |  | 5 |  |  |  |  |  |  |  |  |
| look-alike | software version that looks like an IPv4 address |  |  |  |  |  |  | 2 | 2 |  |  |  |  |
| look-alike | transaction ID, 15 digits, Amex prefix, Luhn-valid |  |  |  |  |  |  | 1 |  |  |  |  |  |
| must keep | ARN (23 digits) | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| must keep | AVS / CVV2 / ECI result code | 2 | 1 |  | 2 |  |  |  |  |  |  | 3 | 3 |
| must keep | BIC |  |  | 2 |  |  |  |  | 1 |  |  |  |  |
| must keep | CAVV (3-D Secure value) |  |  |  |  |  |  |  |  |  |  | 1 | 1 |
| must keep | URL | 4 | 2 | 2 | 3 | 1 | 1 | 2 | 1 | 1 | 1 | 1 | 1 |
| must keep | amount | 5 | 7 | 11 | 11 | 25 | 24 | 3 | 4 | 12 | 8 | 8 | 7 |
| must keep | authorization code | 3 | 3 | 2 | 3 | 2 | 5 | 1 | 1 | 2 | 2 | 2 | 2 |
| must keep | carrier name | 4 | 7 | 1 |  |  |  | 1 | 1 | 4 | 4 | 1 | 1 |
| must keep | case / reference number | 4 | 3 | 8 | 11 | 3 | 3 | 5 | 4 | 4 | 4 | 5 | 5 |
| must keep | city | 6 | 9 | 4 | 1 | 3 | 2 | 1 | 3 | 3 | 2 | 2 | 4 |
| must keep | company name | 10 | 8 | 9 | 10 | 10 | 7 | 5 | 5 | 13 | 8 | 5 | 5 |
| must keep | company registration or tax number | 1 | 1 |  |  | 1 |  |  |  | 1 |  |  |  |
| must keep | country | 1 | 2 |  |  | 1 |  |  |  |  | 2 |  |  |
| must keep | date | 19 | 26 | 20 | 20 | 23 | 15 | 18 | 21 | 17 | 17 | 19 | 13 |
| must keep | date and time | 5 | 6 | 3 | 6 | 5 | 12 | 8 | 3 | 4 | 10 | 1 | 5 |
| must keep | last four digits | 1 | 1 |  | 3 | 1 | 1 | 1 |  |  | 2 | 1 | 1 |
| must keep | merchant descriptor | 2 | 3 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| must keep | order / booking number | 4 | 7 | 2 |  | 3 | 6 | 3 | 3 | 5 | 4 | 13 | 11 |
| must keep | postcode |  |  |  |  |  | 1 |  |  |  |  |  |  |
| must keep | postcode and city | 7 | 10 | 6 | 6 | 7 | 4 | 3 | 4 | 8 | 8 | 6 | 5 |
| must keep | retrieval reference number | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 |
| must keep | tracking number | 5 | 7 | 1 |  |  |  | 1 | 1 | 4 | 3 | 3 | 1 |
| must keep | transaction ID | 1 | 1 | 1 | 1 | 1 | 1 |  | 1 | 1 | 1 | 2 | 1 |
| must keep | vehicle registration plate (rental fleet car) |  |  |  |  |  | 1 |  |  |  |  |  |  |

Tracking formats: DHL in POCVISA131M01, POCVISA133M09, POCVISA137M03; FedEx in POCVISA104M11; Royal Mail in POCVISA104M12, POCVISA132M07, POCVISA133M10; UPS 1Z in POCVISA131M02; other carrier in POCVISA131M02, POCVISA132M08.

## How to score a run

Run the reader and masker over the 36 PDFs, keep the masked text per page, and compare it with `items`:

1. **Miss**: a row whose expected result is not `keep`, and whose value is still readable on its page in the masked text.
2. **Over-mask**: a `keep` row whose value was changed (no longer found verbatim on its page).
3. **Wrong link**: two items in one token group whose tokens carry different hashes, or two items in different groups whose tokens carry the same hash. The `groups` sheet also lists the sets of groups that must keep different tokens (spelling variants, initials, the same surname, phone formats); a shared hash there is a wrong link.
4. **Wrong replacement**: a fixed replacement that differs from the expected text (for example `[CARD ending 1111]` printed where `[PHONE_xxxx]` was expected). Compare tokens by hash; a different TYPE prefix with the right hash is a type-label difference, not a wrong link.

Report rows with `covered by v1` = no separately: a miss or wrong replacement there is a known v1 gap, not a defect. A value printed more than once on a page has one row; it counts as readable if any printing of it is.

## Points to know before running

1. **Phone numbers caught by the card rule.** Two German numbers in POCVISA133M09, `+49 711 6634 2210` and `+49 711 2290 4418`, are 13 digits starting with 4 and pass Luhn, so §3.3 reads them as 13-digit Visa numbers and replaces them before Guardrails sees them. International numbers starting +4x run 12–13 digits; roughly one in ten 13-digit ones will hit. The rows are marked not covered.
2. **An IBAN caught by the card rule.** In POCVISA137M03 the IBAN `DE50 7306 9912 0045 8813 27` holds the run `50 7306 9912 0045 8813` (Maestro prefix 50, 18 digits, Luhn-valid). §3.3 runs before Guardrails, so the IBAN becomes `DE[CARD ending 8813] 27`. IBAN check digits 50 and 56–69 put this risk on about one IBAN in seven. The French IBAN in POCVISA132M08 does not hit and is expected as an IBAN token.
3. **An IMEI caught by the card rule.** IMEIs start 35 (inside JCB's 3528–3589) and end in a Luhn digit, so the device IMEI in POCVISA104M11 will be replaced as a card. It stays hidden, but the 10.4 rows that match device IDs will see `[CARD ending …]`. The round 2 10.4 cases S35 and S49 use IMEIs as device IDs and will be affected the same way.
4. **A transaction ID caught by the card rule.** POCVISA132M07's Tran ID `372995807242889` has an Amex prefix and a valid Luhn digit. §3.4 keeps transaction IDs, but §3.3's exclusion labels are tracking, AWB, order and ARN, so it will be masked. Expected `keep`, covered no.
5. **Exclusion labels.** The Luhn-valid order number `4774586742104` (POCVISA137M04) is kept next to 'Order No.' but expected to be masked where it follows 'reservation' (covered no). The 16-digit Luhn-valid tracking number (POCVISA133M10) is kept next to 'Tracking number' but not after the French 'suivi' (covered no).
6. **CVV look-alikes.** `Prüfnummer 318` on a workshop invoice (POCVISA133M09) and `CVV2 check time 214 ms` in a gateway log (POCVISA104M11) put 3 digits right after a §3.3 CVV label; v1 will mask both. Expected keep, covered no. The other 3-digit look-alikes are a clause or a sentence away from the label and are expected kept.
7. **Last-four forms** (`****1111`, `XXXX-1881`, `************1111`) are expected `keep`: §3.4 ignores a card-number hit on a last-four form. Forms that show the first six as well (`4111 11** **** 1111`, `411111******1111`, `4111-11xx-xxxx-1111`) are expected `[CARD ending nnnn]` under §3.3's 'partly masked numbers included'. If the team reads that clause as covering last-four forms too, flip those rows.
8. **Bank identifiers.** The BIC, the UK sort code and the US routing number are expected `keep`: §3.4 ignores SWIFT_CODE as 'a bank, not a person', takes US_BANK_ACCOUNT_NUMBER but not US_BANK_ROUTING_NUMBER, and Guardrails has no sort-code entity. The account numbers are expected as `[US_BANK_ACCOUNT_NUMBER_xxxx]`. The US one is covered; the two UK 8-digit ones (POCVISA132M07, POCVISA133M10) are marked not covered, because no entity §3.4 takes is defined for UK accounts.
9. **Addresses.** The item is the street part (with flat, floor or door); city, postcode and country are separate keep rows, as the request asked. §3.4 says 'postcode district', so a run that masks the inward part of a full UK postcode (`6RB` in `M20 6RB`) scores an over-mask here; change those rows if compliance rules on districts. Whether Guardrails' address span stops before the city line is one of the things a run measures. Business addresses (merchant, bank, carrier depot, workshop, parcel locker) are expected tokenized: §3.4 has no business-address exemption.
10. **Merchant name.** POCVISA131M02 carries `merchant.name` = Hélène Marchetti, so her name printed in full, with or without capitals, is expected `keep`; `H. Marchetti` and `Hélène` alone are expected tokenized (decision 7 compares whole normalised values). POCVISA133M10 leaves `merchant.name` blank and the descriptor `ATELIER HM CERAMIQUE` carries no name, so every form of her name is tokenized there.
11. **Normalisation.** Token groups assume §3.4's normalisation: lowercase, accents stripped (ü → u, not ue), punctuation and spaces collapsed, phones digits only. The groups were checked under both readings of 'collapsed' (punctuation to a space, or removed) and come out the same. So `María Elena Ortega Villalba`, `Maria Elena Ortega Villalba` and `MARÍA ELENA ORTEGA VILLALBA` share a token, while `ORTEGA VILLALBA, MARIA ELENA`, `A. Ortega`, `Jürgen Köhler` against `Juergen Koehler`, and `+44 7700 900418` against `07700 900418` do not.
12. **One-time codes.** §3.4 maps one-time codes to `[CODE]` but takes no Guardrails entity that detects them (PIN is not taken). The two OTPs and the delivery code are expected `[CODE]` and marked not covered until the build names a detector.
13. **Emails written as 'name (at) domain'**: one typed in a French chat (POCVISA132M08) and one written on a scanned workshop form (POCVISA133M09). Expected `[EMAIL_xxxx]`, each its own token, separate from the normal address; marked not covered, since this form is outside the user@domain pattern.
14. **A date right after a card number.** In POCVISA132M07 the billing table prints `15/01/2026` in the cell next to a Mastercard number; §3.3's 'expiry directly after a card number' can take `01/2026` out of it. Expected keep, covered no. The other MM/YY-style dates (`01/21`, `06/23`, `01/07`) and `Jan 2026` have no expiry label and are expected kept.
15. **Not covered by v1 (Part 6):** track data, the 12-digit Maestro (§3.3 is 13–19 digits), the Spanish DNI and NIE, the non-US passport numbers and MRZ, dates of birth, device IDs, device fingerprints, GPS coordinates and the password-reset token inside a URL. They are expected `mask (no v1 replacement)`.
16. **Catalan.** POCVISA125M05 Merchant p6 is in Catalan. Its PII rows are marked not covered because Catalan is outside Guardrails' language list (Part 9.3); a run there measures what Guardrails does anyway.
17. **IP addresses** are from the ranges reserved for documentation (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24, 2001:db8::/32), plus one private `192.168.1.24`. All are expected `[IP_ADDRESS_xxxx]`. The version numbers `4.12.0.118`, `4.12.1.120`, `7.3.1.204` and `7.3.2.211` are expected `keep` but marked not covered: they are valid dotted quads and nothing in v1 suppresses an IP hit.
18. **Phones** use the UK Ofcom drama ranges, the French ARCEP fiction ranges and the US 555-01xx range where one exists; Spanish and German numbers are invented.

## Verification

- Every row was checked against its page after the PDFs were written: 1489 digital values found verbatim in the page text extracted with pdfplumber and with pdftotext; 68 scanned values found in the text each image was drawn from. Items the generated pages failed to carry: none.
- 69 identifier checks by script, all passed: Luhn on every full card number, the 16-digit tracking number, the 13-digit order number and the Amex-like transaction ID; Luhn failing on the misread number; last four against the replacement; DNI and NIE check letters; IBAN mod-97; ABA routing checksum.
- OCR legibility of the scanned card numbers and PII, with tesseract as a stand-in for Textract: 28 of 33 read back exactly. The rest are where a run's OCR path is tested hardest.
- Card-rule simulation: §3.3's card rule, read aggressively, was run over every page. It hits every full card number in 13–19 digits and, besides those, only the values named in points 1–5.

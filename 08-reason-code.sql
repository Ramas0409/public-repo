-- Office step 8: reason_code gains the agency MCC set (POC db/V39, CL-151) and the VISA 13.1 row is upserted as the
-- seed writes it: catalogue 1.5.0, agency_mccs {4722,7922}, agency_mccs_reviewed {7922}; the four text columns are
-- unchanged in content. Safe to run again.

BEGIN;

ALTER TABLE wdp_ai.reason_code ADD COLUMN IF NOT EXISTS agency_mccs text[] NOT NULL DEFAULT '{}';
ALTER TABLE wdp_ai.reason_code ADD COLUMN IF NOT EXISTS agency_mccs_reviewed text[] NOT NULL DEFAULT '{}';
COMMENT ON COLUMN wdp_ai.reason_code.agency_mccs IS
    'CL-151: the merchant category codes the agency waiting period applies to (section 18 of the register)';
COMMENT ON COLUMN wdp_ai.reason_code.agency_mccs_reviewed IS
    'CL-151: the agency codes on which a run firing G25 or G26 is flagged for review';

-- the reason code's own text (§18): the payload's header, how-to-read, glossary and supplementary header
INSERT INTO wdp_ai.reason_code (network, code, name, rulebook_edition, catalogue_version, grounds_header, how_to_read, terms_used, supplementary_header, agency_mccs, agency_mccs_reviewed) VALUES
  ('VISA', '13.1', 'Merchandise/Services Not Received', '18 April 2026', '1.5.0', 'Reason code {reasonCode} — the merchant''s permitted arguments: {codes}, in register order.', 'How to read this list. Each argument gives the rulebook''s words, then when to nominate it, the evidence that argues it most strongly, and when it does not fit. Nominate an argument whenever the merchant''s records bear on it, even if the evidence is not of the strongest kind: the rules decide later whether it is enough, and a missed argument costs the merchant more than an extra one. Always quote the record you rely on. Reject an argument only when it does not fit this case, or when nothing in the records bears on it. Every argument is listed on every case, but several apply only to one kind of sale or complaint: a remote sale, an in-person sale, an airline ticket, a service booked for a later date, a crypto or NFT purchase; judge from the records whether this case is that kind. The rule''s words govern the final decision; the lines below are deliberately broader, so that no argument is missed. The merchant''s pack is often one file holding many kinds of record: read all of it. Where a section of the case says an exclusion or limit is MET, rely on it: it was worked out from the record, not read from a document.', 'Terms used below.
- Merchandise: goods the cardholder buys, usually physical.
- A service: anything else the merchant provides. For example: travel and transport; accommodation and holidays; event and attraction entry; memberships and subscriptions; lessons and courses; gaming and betting accounts; repairs and appointments. Access over time, such as an account or a subscription, is a service, and so is food and drink served to be eaten or drunk on the premises. Whether a one-off digital download is merchandise or a service is not settled.
- A future service: a service paid for at purchase and due on a later date. It remains a future service once that date has passed.
- An agent: a merchant, such as a travel or ticket agency, that arranges a service another business provides.
- One purchase can include merchandise and services. Each argument answers only its own part: proof the goods arrived does not answer a complaint about the service.', 'Supplementary arguments — preconditions. These show the dispute should not have been raised under this reason code at all. They supplement the arguments above: nominate one whenever the records show it, whether or not an argument above fits, and list only those you nominate. The time-limit arguments and early filing depend on the time-limits section: rely on the results shown there and never count days yourself.', ARRAY['4722','7922']::text[], ARRAY['7922']::text[])
ON CONFLICT (network, code) DO UPDATE SET name = EXCLUDED.name, rulebook_edition = EXCLUDED.rulebook_edition, catalogue_version = EXCLUDED.catalogue_version, grounds_header = EXCLUDED.grounds_header, how_to_read = EXCLUDED.how_to_read, terms_used = EXCLUDED.terms_used, supplementary_header = EXCLUDED.supplementary_header, agency_mccs = EXCLUDED.agency_mccs, agency_mccs_reviewed = EXCLUDED.agency_mccs_reviewed;

COMMIT;

package com.wp.wdp.chargeback.auto.response.letter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.wp.wdp.chargeback.auto.response.letter.HtmlEscape.prose;
import com.wp.wdp.chargeback.auto.response.db.RegisterRows;
import com.wp.wdp.chargeback.auto.response.letter.dto.LetterClaim;
import com.wp.wdp.chargeback.auto.response.letter.dto.LetterContent;
import com.wp.wdp.chargeback.auto.response.letter.dto.LetterContentSection;
import com.wp.wdp.chargeback.auto.response.letter.model.AssembledLetter;
import com.wp.wdp.chargeback.auto.response.letter.model.Exhibits;
import com.wp.wdp.chargeback.auto.response.letter.model.LetterNetworkWords;
import com.wp.wdp.chargeback.auto.response.letter.model.LetterSection;
import com.wp.wdp.chargeback.auto.response.letter.model.LetterTemplates;
import com.wp.wdp.chargeback.auto.response.letter.model.LetterTile;
import com.wp.wdp.chargeback.auto.response.letter.model.ResolvedIssuerName;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseDocument;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFact;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFacts;
import com.wp.wdp.chargeback.auto.response.model.casefile.NamedDocuments;
import com.wp.wdp.chargeback.auto.response.model.check.RecordCheckRow;
import com.wp.wdp.chargeback.auto.response.model.check.RecordChecks;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimitRow;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimits;
import com.wp.wdp.chargeback.auto.response.model.decision.Decision;
import com.wp.wdp.chargeback.auto.response.model.decision.DecisionAmounts;
import com.wp.wdp.chargeback.auto.response.model.register.CatalogueArgument;
import com.wp.wdp.chargeback.auto.response.model.register.CatalogueTimeLimitRule;
import com.wp.wdp.chargeback.auto.response.model.register.ConditionTokens;
import com.wp.wdp.chargeback.auto.response.model.register.DayCounts;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterRow;
import com.wp.wdp.chargeback.auto.response.service.check.ClassifierRecordRules;
import com.wp.wdp.chargeback.auto.response.service.register.ArgumentCatalogue;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;

/** The network letter: the decision, the register and the model's prose laid out as the redesigned page (template 2.0.0). */
public final class ClassifierLetter {
    private static final String NO_VALUE = "—";
    private static final String FIRST_LETTER = "A";

    private ClassifierLetter() {
    }

    public static AssembledLetter assemble(CaseRow row, ArgumentCatalogue catalogue,
                                     Decision decision,
                                     TimeLimits limits,
                                     IssuerFacts issuer,
                                     Map<String, RegisterRow> register,
                                     LetterContent letter, Map<String, String> citationIds,
                                     Map<String, List<Integer>> confidential, String acquirerName) {
        return assemble(row, catalogue, decision, limits, issuer, register, letter, citationIds,
                confidential, acquirerName, null);
    }

    public static AssembledLetter assemble(CaseRow row, ArgumentCatalogue catalogue,
                                     Decision decision,
                                     TimeLimits limits,
                                     IssuerFacts issuer,
                                     Map<String, RegisterRow> register,
                                     LetterContent letter, Map<String, String> citationIds,
                                     Map<String, List<Integer>> confidential,
                                     String acquirerName, LocalDate today) {
        return assemble(row, catalogue, decision, limits, issuer, register, letter, citationIds,
                confidential, acquirerName, today, RecordChecks.none());
    }

    public static AssembledLetter assemble(CaseRow row, ArgumentCatalogue catalogue,
                                     Decision decision,
                                     TimeLimits limits,
                                     IssuerFacts issuer,
                                     Map<String, RegisterRow> register,
                                     LetterContent letter, Map<String, String> citationIds,
                                     Map<String, List<Integer>> confidential,
                                     String acquirerName, LocalDate today, RecordChecks records) {
        LetterTemplates templates = catalogue.letter();
        RecordChecks checks = records == null ? RecordChecks.none() : records;
        List<String> order = draftOrder(decision);
        Map<String, String> factsByCode = sectionFacts(letter);

        List<LetterSection> sections = new ArrayList<>();
        boolean issuerCited = false;
        int number = 0;
        for (String code : order) {
            String bare = RegisterRows.bare(code);
            TimeLimitRow limit = limits.row(bare);
            RecordCheckRow check = checks.row(bare);
            boolean templated = limit != null || check != null;
            String facts = limit != null
                    ? timeLimitFacts(catalogue, bare, limit, issuer, row, null)
                    : check != null
                    ? catalogue.recordLetterParagraph(bare, check.getFigures())
                    : factsByCode.get(catalogue.qualified(bare));
            if (facts == null || facts.isBlank()) {
                continue;
            }
            issuerCited |= templated;
            CatalogueArgument argument = catalogue.argument(bare);
            boolean ground = catalogue.grounds().contains(bare);
            String label = argument == null ? bare : argument.getLabel();
            String heading = templates.getArgumentHeading().replace("{n}", String.valueOf(++number))
                    .replace("{Label}", capitalised(label));
            sections.add(new LetterSection(catalogue.qualified(bare), heading, label,
                    requirementText(catalogue, register, bare), facts, ground, templated));
        }

        LetterClaim claim = claim(letter);
        String claimQuote = claim.getClaimQuote() == null || claim.getClaimQuote().isBlank() ? null
                : claim.getClaimQuote().trim();
        String claimId = claim.getDocumentRef();
        Integer claimPage = claim.getPage();
        issuerCited |= claimQuote != null;

        List<String> citable = new ArrayList<>();
        citable.add(summaryBasis(letter));
        citable.add(claim.getResponse());
        sections.forEach(section -> citable.add(section.getFacts()));
        if (claimId != null && claimPage != null) {
            citable.add("[" + claimId + " p" + claimPage + "]");
        }

        Map<String, Set<Integer>> templatePages = new LinkedHashMap<>();
        for (LetterSection section : sections) {
            if (!section.getTemplated()) {
                continue;
            }
            for (String key : templateKeys(catalogue, RegisterRows.bare(section.getCode()), issuer)) {
                IssuerFact fact = issuer == null || key == null ? null : issuer.fact(key);
                String ref = fact == null || fact.getDocumentRef() == null ? firstIssuerRef(row) : fact.getDocumentRef();
                templatePages.computeIfAbsent(ref, missing -> new LinkedHashSet<>()).add(exhibitPage(issuer, key));
            }

            RecordCheckRow check = checks.row(RegisterRows.bare(section.getCode()));
            if (check != null && issuer != null && issuer.getFacts() != null && !issuer.getFacts().isEmpty()
                    && ClassifierRecordRules.MISSING_CERTIFICATION.equals(check.getRuleKind())) {
                templatePages.computeIfAbsent(firstIssuerRef(row), missing -> new LinkedHashSet<>())
                        .add(answeredPage(issuer));
            }
        }
        Exhibits exhibits = LetterExhibits.build(row, citable, citationIds, issuerCited,
                confidential, templatePages);
        NamedDocuments named = LetterDocuments.of(templates, exhibits.getExhibits());

        for (int index = 0; index < sections.size(); index++) {
            LetterSection section = sections.get(index);
            String bare = RegisterRows.bare(section.getCode());
            if (!section.getTemplated() || limits.row(bare) == null) {
                continue;
            }
            String facts = timeLimitFacts(catalogue, bare, limits.row(bare), issuer, row, named);
            if (facts != null && !facts.isBlank()) {
                sections.set(index, new LetterSection(section.getCode(), section.getHeading(), section.getLabel(),
                        section.getRequirementText(), facts, section.getIsGround(), true));
            }
        }

        DecisionAmounts amounts = decision.getAmounts();
        String currency = amounts.getDisputedCurrency();
        String contested = money(amounts.getContested(), currency);
        String disputed = money(amounts.getDisputed(), currency);
        String accepted = money(amounts.getAccepted(), currency);
        boolean partial = amounts.getAccepted() != null && amounts.getAccepted().signum() > 0;

        LetterNetworkWords words = templates.network(catalogue.network());
        if (words == null || words.getKicker() == null || words.getRuleReferencesHeading() == null) {
            throw new ServiceStateException("network " + catalogue.network() + " has no block in the letter template");
        }
        String kicker = words.getKicker().replace("{reasonCode}", orEmpty(catalogue.reasonCode()))
                        .replace("{REASON CODE NAME}", orEmpty(catalogue.reasonCodeName()).toUpperCase(Locale.ROOT));
        LetterSection lead = sections.isEmpty() ? null : sections.get(0);
        String headline = lead == null ? ""
                : (lead.getIsGround() ? templates.getHeadlineGround() : templates.getHeadlinePrecondition())
                        .replace("{leadLabel}", lead.getLabel());

        ResolvedIssuerName issuerName = IssuerName.resolve(row, templates.getDefaultIssuerName());
        String acquirer = orEmpty(acquirerName).trim();
        String merchant = present(row.getMerchantName()) ? row.getMerchantName().trim() : orEmpty(row.getMerchantDescriptor());
        String parties = templates.getParties().replace("{issuer}", issuerName.getName())
                .replace("{acquirer}", acquirer).replace("{merchant}", merchant);

        String networkCase = present(row.getNetworkCaseNumber()) ? row.getNetworkCaseNumber().trim() : null;
        List<LetterTile> tiles = new ArrayList<>();
        tiles.add(new LetterTile(templates.getTileNetworkCase(), networkCase == null ? NO_VALUE : networkCase, false));
        tiles.add(new LetterTile(templates.getTileArn(), orDash(row.getArn()), false));
        tiles.add(new LetterTile(templates.getTileCard(),
                (IssuerName.cardLabel(row) + " ****" + orEmpty(row.getCardLastFour())).trim(), false));
        String transactionAmount = money(row.getTransactionAmount(), row.getPurchaseCurrency());
        tiles.add(new LetterTile(templates.getTileTransaction(), templates.getTransactionValue()
                .replace("{transactionDate}", orDash(longDate(row.getTransactionDate())))
                .replace("{transactionAmount}", orDash(transactionAmount)), false));
        tiles.add(new LetterTile(templates.getTileDisputed(), orDash(disputed), false));
        if (partial) {
            tiles.add(new LetterTile(templates.getTileAccepted(), accepted, false));
        }
        tiles.add(new LetterTile(templates.getTileContested(), orDash(contested), true));

        List<String> amountNotes = new ArrayList<>();
        boolean otherCurrency = present(currency) && present(row.getPurchaseCurrency())
                && !currency.trim().equalsIgnoreCase(row.getPurchaseCurrency().trim());
        if (!otherCurrency && amounts.getDisputed() != null && row.getTransactionAmount() != null
                && amounts.getDisputed().compareTo(row.getTransactionAmount()) > 0) {
            amountNotes.add(templates.getAmountNoteExceeds().replace("{disputed}", disputed)
                    .replace("{transactionAmount}", transactionAmount));
        }
        if (otherCurrency) {
            amountNotes.add(templates.getAmountNoteCurrency().replace("{transactionCurrency}", row.getPurchaseCurrency().trim())
                    .replace("{disputeCurrency}", currency.trim()));
        }

        String basis = orEmpty(LetterCitations.resolve(summaryBasis(letter), citationIds, named, templates));
        String summary = (partial ? templates.getResponsePartial() : templates.getResponseFull())
                .replace("{accepted}", orEmpty(accepted)).replace("{contested}", orEmpty(contested))
                .replace("{summaryBasis}", basis).replaceAll("\\s+", " ").trim();

        String claimLine = claimQuote == null ? null : templates.getClaim().replace("{claimQuote}", claimQuote);
        String claimCitation = claimQuote == null || claimId == null || claimPage == null ? null
                : LetterCitations.render(List.of(Map.entry(claimId.toUpperCase(Locale.ROOT), claimPage)), citationIds,
                        named, templates);

        String reference = networkCase != null
                ? templates.getRequestReference().replace("{networkCaseNumber}", networkCase)
                : templates.getRequestReferenceArn().replace("{arn}", orEmpty(row.getArn()));
        String request = (partial ? templates.getRequestPartial() : templates.getRequestFull())
                .replace("{contested}", orEmpty(contested)).replace("{reference}", reference).trim();

        List<String> attachments = LetterDocuments.attachments(templates, named);
        List<String> ruleReferences = new ArrayList<>();
        Set<String> printed = new HashSet<>();
        for (LetterSection section : sections) {
            if (section.getRequirementText() != null && printed.add(section.getRequirementText())) {
                ruleReferences.add(templates.getRuleReferenceEntry().replace("{Label}", capitalised(section.getLabel()))
                        .replace("{ruleText}", section.getRequirementText()));
            }
        }

        String footer = (networkCase != null
                ? templates.getFooter().replace("{networkCaseNumber}", networkCase)
                : templates.getFooterNoNetworkCase()).replace("{arn}", orEmpty(row.getArn()));
        String title = templates.getPdfTitle().replace("{networkCaseNumber}",
                networkCase != null ? networkCase : orEmpty(row.getArn()));

        LocalDate letterDate = today == null ? LocalDate.now() : today;
        String ruleHeading = words.getRuleReferencesHeading();
        String html = html(templates, kicker, letterDate, headline, parties, tiles, amountNotes, summary, claimLine,
                claimCitation, sections, request, attachments, named, ruleHeading, citationIds);
        String plain = text(templates, kicker, letterDate, headline, parties, tiles, amountNotes, summary, claimLine,
                claimCitation, sections, request, attachments, ruleHeading, ruleReferences, named, citationIds);
        return new AssembledLetter(kicker, letterDate, headline, parties, List.copyOf(tiles), List.copyOf(amountNotes),
                summary, claimLine, claimCitation, List.copyOf(sections), request, attachments,
                List.copyOf(ruleReferences), exhibits, named, issuerName, footer, title, html, plain);
    }

    static String requirementText(ArgumentCatalogue catalogue, Map<String, RegisterRow> register,
                                  String bare) {
        RegisterRow row = register.get(bare);

        return row == null ? null : stripFootnotes(row.getRequirementText());
    }

    static final java.util.regex.Pattern FOOTNOTE_MARKER = java.util.regex.Pattern.compile(
            "(?<=[A-Za-z]\\.?)\\d{1,2}(?:,\\d{1,2})*(?=[\\s,.;:)]|$)");

    public static String stripFootnotes(String requirementText) {
        return requirementText == null ? null : FOOTNOTE_MARKER.matcher(requirementText).replaceAll("");
    }

    public static String timeLimitFacts(ArgumentCatalogue catalogue, String bare,
                                 TimeLimitRow limit, IssuerFacts issuer,
                                 CaseRow row) {
        return timeLimitFacts(catalogue, bare, limit, issuer, row, null);
    }

    public static String timeLimitFacts(ArgumentCatalogue catalogue, String bare,
                                 TimeLimitRow limit, IssuerFacts issuer,
                                 CaseRow row, NamedDocuments named) {
        return timeLimitFacts(catalogue, bare, limit, issuer, row, named, true);
    }

    /** The paragraph as the questionnaire files it: its dates as YYYY-MM-DD, as the body has always carried them. */
    public static String questionnaireTimeLimitFacts(ArgumentCatalogue catalogue, String bare, TimeLimitRow limit,
                                                     IssuerFacts issuer, CaseRow row) {
        return timeLimitFacts(catalogue, bare, limit, issuer, row, null, false);
    }

    private static String timeLimitFacts(ArgumentCatalogue catalogue, String bare, TimeLimitRow limit,
                                         IssuerFacts issuer, CaseRow row, NamedDocuments named, boolean worded) {
        LetterTemplates templates = catalogue.letter();
        String template = catalogue.timeLimitLetterParagraph(bare);
        if (template == null || limit.absoluteDayCount() == null) {
            return null;
        }

        String field = citedIssuerDate(catalogue, bare);
        String expectedDateKey = catalogue.isThirteenOne()
                ? "cardholder.expectedReceiptDate" : field;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("{n}", String.valueOf(limit.absoluteDayCount()));
        values.put("{processDate}", paragraphDate(worded, row.getProcessingDate()));
        values.put("{reportedDate}", paragraphDate(worded, row.getDisputeReportedDate()));
        values.put("{saleDate}", paragraphDate(worded, row.getTransactionDate()));
        values.put("{expectedDate}", paragraphDate(worded, date(issuer, expectedDateKey)));
        values.put("{cancellationDate}", paragraphDate(worded, date(issuer, "issuer.merchantCancellationDate")));
        values.put("{claimDate}", paragraphDate(worded, date(issuer, BONDING_CLAIM_DATE)));
        values.put("{letterDate}", paragraphDate(worded, date(issuer, BONDING_LETTER_DATE)));
        values.put("{bk}", bankruptcy(templates, issuer, named, row));
        values.put("{fx}", exhibitReference(templates, issuer, field, named, row));

        String out = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (!out.contains(value.getKey())) {
                continue;
            }

            if (value.getValue() == null) {
                return null;
            }
            out = out.replace(value.getKey(), value.getValue());
        }
        return out.trim();
    }

    static final List<String> EXPECTED_DATE_ROWS = List.of("G32", "G33", "G34", "G42");
    static final List<String> CANCELLATION_ROWS = List.of("G36", "G25", "G26");
    static final List<String> BONDING_CLAIM_ROWS = List.of("G27");
    static final List<String> BONDING_LETTER_ROWS = List.of("G23");
    static final String BONDING_CLAIM_DATE = "bonding.reimbursementClaimSubmittedDate";
    static final String BONDING_LETTER_DATE = "bonding.letterOrAdviceDate";

    /** The issuer date a time-limit paragraph cites in words, by row. */
    static String citedIssuerDate(ArgumentCatalogue catalogue, String bare) {
        if (!catalogue.isThirteenOne()) {
            CatalogueTimeLimitRule rule = catalogue.timeLimitRule(bare);
            if (rule == null) {
                return null;
            }
            String counted = DayCounts.issuerFromField(rule.getDayCount());
            if (counted != null) {
                return counted;
            }
            return rule.getRequires().isEmpty() ? null : ConditionTokens.issuerKey(rule.getRequires().get(0));
        }
        if (EXPECTED_DATE_ROWS.contains(bare)) {
            return "cardholder.expectedReceiptDate";
        }
        if (CANCELLATION_ROWS.contains(bare)) {
            return "issuer.merchantCancellationDate";
        }
        if (BONDING_CLAIM_ROWS.contains(bare)) {
            return BONDING_CLAIM_DATE;
        }
        return BONDING_LETTER_ROWS.contains(bare) ? BONDING_LETTER_DATE : null;
    }

    static String exhibitReference(LetterTemplates templates, IssuerFacts issuer, String key) {
        return exhibitReference(templates, issuer, key, null, null);
    }

    static String exhibitReference(LetterTemplates templates, IssuerFacts issuer, String key,
                                   NamedDocuments named, CaseRow row) {
        IssuerFact fact = issuer == null || key == null ? null : issuer.fact(key);
        String letter = fact == null || named == null || fact.getDocumentRef() == null ? null
                : named.letter(fact.getDocumentRef());
        return templates.getAttachmentCitation()
                .replace("{letter}", letter == null ? issuerDocumentLetter(named, row) : letter)
                .replace("{page}", String.valueOf(exhibitPage(issuer, key)));
    }

    static int exhibitPage(IssuerFacts issuer, String key) {
        IssuerFact fact = issuer == null || key == null ? null : issuer.fact(key);
        if (fact != null && fact.isAnswered() && fact.getPage() != null) {
            return fact.getPage();
        }
        return answeredPage(issuer);
    }

    static List<String> templateKeys(ArgumentCatalogue catalogue, String bare,
                                     IssuerFacts issuer) {
        String template = catalogue.timeLimitLetterParagraph(bare);
        if (template == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        if (template.contains("{fx}")) {
            keys.add(citedIssuerDate(catalogue, bare));
        }

        if (template.contains("{bk}")
                && "false".equalsIgnoreCase(issuer == null ? null
                        : issuer.value("issuer.certifiesMerchantInsolvent"))) {
            keys.add("issuer.certifiesMerchantInsolvent");
        }
        return keys;
    }

    static String firstIssuerRef(CaseRow row) {
        List<CaseDocument> documents = row == null ? List.of() : row.issuerDocuments();
        return documents.isEmpty() ? null : documents.get(0).getRef();
    }

    static int answeredPage(IssuerFacts issuer) {
        if (issuer == null || issuer.getFacts() == null) {
            return 1;
        }
        return issuer.getFacts().stream()
                .filter(fact -> fact.isAnswered() && fact.getPage() != null)
                .map(IssuerFact::getPage)
                .min(Integer::compareTo).orElse(1);
    }

    static String issuerDocumentLetter(NamedDocuments named, CaseRow row) {
        if (named != null && row != null) {
            for (CaseDocument document : row.issuerDocuments()) {
                String letter = named.letter(document.getRef());
                if (letter != null) {
                    return letter;
                }
            }
        }
        return FIRST_LETTER;
    }

    static String bankruptcy(LetterTemplates templates, IssuerFacts issuer) {
        return bankruptcy(templates, issuer, null, null);
    }

    static String bankruptcy(LetterTemplates templates, IssuerFacts issuer,
                             NamedDocuments named, CaseRow row) {
        String value = issuer == null ? null : issuer.value("issuer.certifiesMerchantInsolvent");
        if ("false".equalsIgnoreCase(value)) {
            return templates.getBankruptcyRecorded().replace("{fx}",
                    exhibitReference(templates, issuer, "issuer.certifiesMerchantInsolvent", named, row));
        }
        return templates.getBankruptcyNotRecorded();
    }

    private static LocalDate date(IssuerFacts issuer, String key) {
        String value = issuer == null ? null : issuer.value(key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static List<String> draftOrder(Decision decision) {
        List<String> out = new ArrayList<>();
        if (decision.getDraft() == null || decision.leadCode() == null) {
            return out;
        }
        out.add(decision.leadCode());
        Object supplements = decision.getDraft().get("supplementaryCodes");
        if (supplements instanceof List<?> list) {
            list.forEach(code -> out.add(String.valueOf(code)));
        }
        return out;
    }

    static Map<String, String> sectionFacts(LetterContent letter) {
        Map<String, String> out = new LinkedHashMap<>();
        List<LetterContentSection> sections = letter == null ? null : letter.getSections();
        for (LetterContentSection section : sections == null ? List.<LetterContentSection>of() : sections) {
            if (section.getCode() != null && section.getFacts() != null) {
                out.put(section.getCode(), section.getFacts());
            }
        }
        return out;
    }

    static LetterClaim claim(LetterContent letter) {
        LetterClaim claim = letter == null ? null : letter.getClaimAndResponse();
        return claim == null ? new LetterClaim() : claim;
    }

    static String summaryBasis(LetterContent letter) {
        return letter == null ? null : letter.getSummaryBasis();
    }

    /** A ground's facts as rows: one per sentence, the sentence's own citation beside it. */
    static List<List<String>> factRows(LetterSection section, Map<String, String> citationIds, NamedDocuments named,
                                       LetterTemplates templates) {
        List<List<String>> rows = new ArrayList<>();
        String facts = section.getFacts();
        if (section.getTemplated()) {
            rows.add(LetterCitations.toMargin(facts, templates));
            return rows;
        }
        for (int[] span : FigureCheck.sentences(facts, FigureCheck.mask(facts, null))) {
            String sentence = facts.substring(span[0], span[1]);
            String text = LetterCitations.withoutMarkers(sentence);
            if (text == null || text.isBlank()) {
                continue;
            }
            rows.add(List.of(text, orEmpty(LetterCitations.citation(sentence, citationIds, named, templates))));
        }
        return rows;
    }

    private static String html(LetterTemplates templates, String kicker, LocalDate letterDate, String headline,
                               String parties, List<LetterTile> tiles, List<String> amountNotes, String summary,
                               String claim, String claimCitation, List<LetterSection> sections, String request,
                               List<String> attachments, NamedDocuments named, String ruleHeading,
                               Map<String, String> citationIds) {
        StringBuilder page = new StringBuilder();
        page.append("<table><tr><td class=\"kicker\">").append(prose(kicker)).append("</td><td class=\"date\">")
                .append(prose(longDate(letterDate))).append("</td></tr></table>");
        page.append("<h1>").append(prose(headline)).append("</h1>");
        page.append("<div class=\"parties\">").append(prose(parties)).append("</div>");

        int firstRow = Math.min(3, tiles.size());
        page.append("<table class=\"tiles\"><tr>").append(tileCells(tiles.subList(0, firstRow)))
                .append("</tr><tr class=\"space\"><td colspan=\"").append(firstRow * 2 - 1).append("\"></td></tr></table>");
        if (tiles.size() > firstRow) {
            page.append("<table class=\"tiles\"><tr>").append(tileCells(tiles.subList(firstRow, tiles.size())))
                    .append("</tr></table>");
        }
        for (String note : amountNotes) {
            page.append("<div class=\"note\">").append(prose(note)).append("</div>");
        }

        page.append("<div class=\"block\"><h2>").append(prose(templates.getResponseHeading())).append("</h2><p>")
                .append(prose(summary)).append("</p></div>");
        if (claim != null) {
            page.append("<div class=\"block\"><h2>").append(prose(templates.getClaimHeading())).append("</h2>")
                    .append("<table><tr><td class=\"quote\">").append(prose(claim)).append("</td><td class=\"ref\">")
                    .append(prose(orEmpty(claimCitation))).append("</td></tr></table></div>");
        }

        for (int index = 0; index < sections.size(); index++) {
            LetterSection section = sections.get(index);
            if (index == 0) {
                page.append("<div class=\"block\"><h2>").append(prose(templates.getArgumentsHeading())).append("</h2>");
            }
            page.append("<div class=\"argument\"><div class=\"argtitle\">").append(prose(section.getHeading()))
                    .append("</div><table class=\"facts\">");
            for (List<String> row : factRows(section, citationIds, named, templates)) {
                page.append("<tr><td>").append(prose(row.get(0))).append("</td><td class=\"ref\">")
                        .append(prose(row.get(1))).append("</td></tr>");
            }
            page.append("</table></div>");
            if (index == 0) {
                page.append("</div>");
            }
        }

        page.append("<div class=\"block\"><h2>").append(prose(templates.getRequestHeading())).append("</h2><p>")
                .append(prose(request)).append("</p></div>");

        if (!attachments.isEmpty()) {
            page.append("<div class=\"block\"><h2>").append(prose(templates.getAttachmentsHeading()))
                    .append("</h2><div class=\"attachments\">");
            for (String entry : attachments) {
                page.append("<p>").append(prose(entry)).append("</p>");
            }
            page.append("</div></div>");
        }

        boolean anyRule = sections.stream().anyMatch(section -> section.getRequirementText() != null);
        if (anyRule && ruleHeading != null) {
            page.append("<div class=\"rules\"><div class=\"rhead\">").append(prose(ruleHeading)).append("</div>");
            Set<String> drawn = new HashSet<>();
            for (LetterSection section : sections) {
                if (section.getRequirementText() != null && drawn.add(section.getRequirementText())) {
                    page.append("<p><b>").append(prose(capitalised(section.getLabel()))).append(".</b> ")
                            .append(prose(section.getRequirementText())).append("</p>");
                }
            }
            page.append("</div>");
        }
        return page.toString();
    }

    private static String tileCells(List<LetterTile> tiles) {
        StringBuilder out = new StringBuilder();
        String width = tiles.size() > 3 ? " four" : " three";
        for (int index = 0; index < tiles.size(); index++) {
            LetterTile tile = tiles.get(index);
            if (index > 0) {
                out.append("<td class=\"gap\"></td>");
            }
            out.append("<td class=\"tile").append(width).append(tile.isAccent() ? " accent" : "").append("\"><div class=\"tlabel\">")
                    .append(prose(tile.getLabel())).append("</div><div class=\"tvalue\">").append(prose(tile.getValue()))
                    .append("</div></td>");
        }
        return out.toString();
    }

    private static String text(LetterTemplates templates, String kicker, LocalDate letterDate, String headline,
                               String parties, List<LetterTile> tiles, List<String> amountNotes, String summary,
                               String claim, String claimCitation, List<LetterSection> sections, String request,
                               List<String> attachments, String ruleHeading, List<String> ruleReferences,
                               NamedDocuments named, Map<String, String> citationIds) {
        List<String> out = new ArrayList<>();
        out.add(kicker);
        out.add(longDate(letterDate));
        out.add(headline);
        out.add(parties);
        tiles.forEach(tile -> out.add(tile.getLabel() + ": " + tile.getValue()));
        out.addAll(amountNotes);
        out.add(templates.getResponseHeading());
        out.add(summary);
        if (claim != null) {
            out.add(templates.getClaimHeading());
            out.add(claimCitation == null ? claim : claim + " — " + claimCitation);
        }
        if (!sections.isEmpty()) {
            out.add(templates.getArgumentsHeading());
        }
        for (LetterSection section : sections) {
            out.add(section.getHeading());
            for (List<String> row : factRows(section, citationIds, named, templates)) {
                out.add(row.get(1).isEmpty() ? row.get(0) : row.get(0) + " — " + row.get(1));
            }
        }
        out.add(templates.getRequestHeading());
        out.add(request);
        if (!attachments.isEmpty()) {
            out.add(templates.getAttachmentsHeading());
            out.addAll(attachments);
        }
        if (!ruleReferences.isEmpty() && ruleHeading != null) {
            out.add(ruleHeading);
            out.addAll(ruleReferences);
        }
        return String.join("\n", out);
    }

    public static String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        int digits = 2;
        try {
            if (currency != null && !currency.isBlank()) {
                digits = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT))
                        .getDefaultFractionDigits();
            }
        } catch (RuntimeException exception) {
            digits = 2;
        }
        String figure = amount.setScale(Math.max(digits, 0), RoundingMode.HALF_UP).toPlainString();
        return currency == null || currency.isBlank() ? figure : figure + " " + currency;
    }

    private static String paragraphDate(boolean worded, LocalDate date) {
        return worded ? longDate(date) : date == null ? null : date.toString();
    }

    /** A date as the letter prints it outside quotes: "3 Jun 2025". */
    public static String longDate(LocalDate date) {
        return DisplayFormats.date(date);
    }

    static String capitalised(String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        return label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
    }

    private static boolean present(String text) {
        return text != null && !text.isBlank();
    }

    private static String orDash(String text) {
        return present(text) ? text : NO_VALUE;
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

}

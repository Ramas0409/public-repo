package com.wp.wdp.chargeback.auto.response.service.register;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.wp.wdp.chargeback.auto.response.db.RegisterRows;
import com.wp.wdp.chargeback.auto.response.letter.model.LetterNetworkWords;
import com.wp.wdp.chargeback.auto.response.letter.model.LetterTemplates;
import com.wp.wdp.chargeback.auto.response.model.register.CatalogueArgument;
import com.wp.wdp.chargeback.auto.response.model.register.CatalogueRecordRule;
import com.wp.wdp.chargeback.auto.response.model.register.CatalogueTimeLimitRule;
import com.wp.wdp.chargeback.auto.response.model.register.DayCounts;
import com.wp.wdp.chargeback.auto.response.model.register.MerchantDocumentType;
import com.wp.wdp.chargeback.auto.response.model.register.ReasonCodeText;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterRecordRule;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterRow;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterTimeLimitRule;
import com.wp.wdp.chargeback.auto.response.model.register.RowRoles;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireDefinition;
import com.wp.wdp.chargeback.auto.response.service.check.ClassifierRecordRules;
import com.wp.wdp.chargeback.auto.response.service.check.ClassifierTimeLimits;
import com.wp.wdp.chargeback.auto.response.exception.ServiceArgumentException;

/** The argument list, as the database holds it. */
public final class ArgumentCatalogue {
    private final ReasonCodeText text;
    private final Map<String, RegisterRow> rows;
    private final Map<String, RegisterTimeLimitRule> timeLimitRules;
    private final Map<String, RegisterRecordRule> recordRules;
    private final LetterTemplates letter;
    private final List<RegisterRow> rendered;

    public static final String JUDGEMENT = "JUDGEMENT";
    public static final String TIME_LIMIT = "TIME_LIMIT";
    public static final String RECORD = "RECORD";
    public static final String NO_REASON_CODE = "wdp_ai.reason_code has no row for the reason code";
    public static final String THIRTEEN_ONE = "13.1";
    public static final String REASON_CODE_SHAPE = "\\d{1,2}\\.\\d{1,2}|\\d{4}";

    public ArgumentCatalogue(ReasonCodeText text, Map<String, RegisterRow> rows,
                             Map<String, RegisterTimeLimitRule> rules, LetterTemplates letter) {
        this(text, rows, rules, Map.of(), letter);
    }

    public ArgumentCatalogue(ReasonCodeText text, Map<String, RegisterRow> rows,
                             Map<String, RegisterTimeLimitRule> rules,
                             Map<String, RegisterRecordRule> recordRules, LetterTemplates letter) {
        if (text == null) {
            throw new ServiceArgumentException(NO_REASON_CODE);
        }
        this.text = text;
        this.rows = Map.copyOf(rows);
        this.timeLimitRules = Map.copyOf(rules);
        this.recordRules = Map.copyOf(recordRules);
        this.letter = letter;
        this.rendered = rows.values().stream().filter(RegisterRow::isRendered)
                .sorted(Comparator.comparingInt(RegisterRow::getRenderOrder)).toList();
    }

    public static ArgumentCatalogue load(RegisterRows register, Long reasonCodeId, LetterTemplates letter) {
        return new ArgumentCatalogue(register.reasonCodeText(reasonCodeId), register.all(reasonCodeId),
                register.timeLimits(reasonCodeId), register.recordRules(reasonCodeId), letter);
    }

    public ArgumentCatalogue forRegions(List<String> includedRegions) {
        Map<String, RegisterRow> kept = new LinkedHashMap<>();
        for (RegisterRow row : rows.values()) {
            if (!row.isRendered() || includedRegions.contains(row.getRegion())) {
                kept.put(row.getCode(), row);
            }
        }
        Map<String, RegisterTimeLimitRule> keptTimeLimits = new LinkedHashMap<>();
        timeLimitRules.forEach((code, rule) -> {
            if (kept.containsKey(code)) {
                keptTimeLimits.put(code, rule);
            }
        });
        Map<String, RegisterRecordRule> keptRecordRules = new LinkedHashMap<>();
        recordRules.forEach((code, rule) -> {
            if (kept.containsKey(code)) {
                keptRecordRules.put(code, rule);
            }
        });
        return new ArgumentCatalogue(text, kept, keptTimeLimits, keptRecordRules, letter);
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        LetterNetworkWords words = letter == null ? null : letter.network(text.getNetwork());
        if (letter != null && (words == null || isBlank(words.getKicker()) || isBlank(words.getRuleReferencesHeading()))) {
            problems.add("network " + text.getNetwork() + " has no block in the letter template "
                    + "(scenario/letter-template.json)");
        }
        if (isBlank(text.getNetworkName())) {
            problems.add("network " + text.getNetwork() + " has no display name in NetworkNames");
        }
        for (Map.Entry<String, String> field : Map.of(
                "grounds_header", nullToEmpty(text.getHeaderTemplate()), "how_to_read", nullToEmpty(text.getHowToRead()),
                "terms_used", nullToEmpty(text.getTermsUsedBelow()),
                "supplementary_header", nullToEmpty(text.getSupplementaryHeader())).entrySet()) {
            if (field.getValue().isBlank()) {
                problems.add("reason_code." + field.getKey() + " is blank");
            }
        }
        for (RegisterRow row : rendered) {
            boolean timeLimit = "TIME_LIMIT".equals(row.getArgumentClass());
            boolean hasRule = timeLimitRules.containsKey(row.getCode());
            if (timeLimit && !hasRule) {
                problems.add(row.getCode() + " is a TIME_LIMIT argument and has no requirement_time_limit row");
            }
            if (timeLimit && hasRule && isBlank(timeLimitRules.get(row.getCode()).getLetterParagraph())) {
                problems.add(row.getCode() + " is a TIME_LIMIT argument and its requirement_time_limit row carries "
                        + "no letter paragraph");
            }
            if (RECORD.equals(row.getArgumentClass()) && recordRules.containsKey(row.getCode())
                    && isBlank(recordRules.get(row.getCode()).getLetterParagraph())) {
                problems.add(row.getCode() + " is a RECORD argument and its requirement_record_rule row carries "
                        + "no letter paragraph");
            }
        }
        for (String code : timeLimitRules.keySet()) {
            RegisterRow row = rows.get(code);
            if (row == null || !"TIME_LIMIT".equals(row.getArgumentClass())) {
                problems.add(code + " has a requirement_time_limit row and is not a TIME_LIMIT argument");
            }
            for (String condition : timeLimitRules.get(code).getRequires()) {
                if (!ClassifierTimeLimits.CONDITIONS.contains(condition)) {
                    problems.add(qualified(code) + ": condition '" + condition
                            + "' is not one the orchestrator evaluates");
                }
            }
            String dayCount = timeLimitRules.get(code).getDayCount();
            if (!isThirteenOne() && !DayCounts.isKnown(dayCount)) {
                problems.add(qualified(code) + ": day count '" + dayCount + "' is not one the orchestrator counts");
            }
        }

        for (RegisterRow row : rendered) {
            if (RECORD.equals(row.getArgumentClass()) && !recordRules.containsKey(row.getCode())) {
                problems.add(row.getCode() + " is a RECORD argument and has no requirement_record_rule row");
            }
        }
        for (String code : recordRules.keySet()) {
            RegisterRow row = rows.get(code);
            if (row == null || !RECORD.equals(row.getArgumentClass())) {
                problems.add(code + " has a requirement_record_rule row and is not a RECORD argument");
            }
            if (!ClassifierRecordRules.KINDS.contains(recordRules.get(code).getRuleKind())) {
                problems.add(qualified(code) + ": record-rule kind '" + recordRules.get(code).getRuleKind()
                        + "' is not one the orchestrator settles");
            }
            if (!isThirteenOne()
                    && ClassifierRecordRules.MISSING_CERTIFICATION.equals(recordRules.get(code).getRuleKind())) {
                problems.add(qualified(code) + ": the missing-certification set is 13.1's");
            }
        }
        if (rendered.isEmpty()) {
            problems.add("no requirement row carries a render_order: the payload would be empty");
        }
        Map<String, List<String>> byRole = new LinkedHashMap<>();
        for (RegisterRow row : rows.values()) {
            if (row.getRole() == null) {
                continue;
            }
            if (!RowRoles.ALL.contains(row.getRole())) {
                problems.add(qualified(row.getCode()) + ": role '" + row.getRole() + "' is not one the orchestrator acts on");
            }
            byRole.computeIfAbsent(row.getRole(), role -> new ArrayList<>()).add(row.getCode());
        }
        byRole.forEach((role, codes) -> {
            if (codes.size() > 1 && !RowRoles.SHARED.contains(role)) {
                codes.sort(Comparator.comparingInt(ArgumentCatalogue::number));
                problems.add("role " + role + " is carried by " + codes.size() + " rows (" + String.join(", ", codes)
                        + "); it is one row's");
            }
        });
        return List.copyOf(problems);
    }

    public String version() {
        return text.getCatalogueVersion();
    }

    public String network() {
        return text.getNetwork();
    }

    public String reasonCode() {
        return text.getReasonCode();
    }

    /** The network's display name, as NetworkNames holds it; null for a network it does not name. */
    public String networkName() {
        return text.getNetworkName();
    }

    /** The clause the letter instructions print for what the model must never mention. */
    public String neverMention() {
        return text.getNeverMention();
    }

    /** The phrases a letter may not use, as the register's section 18 lists them. */
    public List<String> bannedPhrases() {
        return text.getBannedPhrases();
    }

    public String reasonCodeName() {
        return text.getReasonCodeName();
    }

    /** The code's name in plain words for the summary's kicker, else its register name. */
    public String plainName() {
        return text.getPlainName() == null || text.getPlainName().isBlank() ? text.getReasonCodeName()
                : text.getPlainName();
    }

    /** What a case with nothing to argue asks the merchant for, as merchant-document keys. */
    public List<String> firstAsks() {
        return text.getFirstAsks();
    }

    /** The documents the summary may ask a merchant for, in the summary's order. */
    public List<MerchantDocumentType> merchantDocumentTypes() {
        return text.getMerchantDocumentTypes();
    }

    /** The merchant-document type of a key, or null where the code declares none. */
    public MerchantDocumentType merchantDocumentType(String key) {
        for (MerchantDocumentType type : text.getMerchantDocumentTypes()) {
            if (type.getKey().equals(key)) {
                return type;
            }
        }
        return null;
    }

    /** The documents that would make a row strong, in the order to ask; empty for an unknown row. */
    public List<String> merchantDocuments(String code) {
        RegisterRow row = row(code);
        return row == null ? List.of() : row.getMerchantDocuments();
    }

    /** The response type the code files on - representment, or allocprearb for 10.4. */
    public String responseType() {
        return text.getResponseType() == null ? QuestionnaireDefinition.REPRESENTMENT : text.getResponseType();
    }

    public String rulebookEdition() {
        return text.getRulebookEdition();
    }

    public String headerTemplate() {
        return text.getHeaderTemplate();
    }

    public String howToRead() {
        return text.getHowToRead();
    }

    public String termsUsedBelow() {
        return text.getTermsUsedBelow();
    }

    public String supplementaryHeader() {
        return text.getSupplementaryHeader();
    }

    /** The merchant category codes the agency waiting period applies to (the register's section 18). */
    public List<String> agencyMccs() {
        return text.getAgencyMccs();
    }

    /** The agency codes on which a run that fires G25 or G26 is flagged for review. */
    public List<String> agencyMccsReviewed() {
        return text.getAgencyMccsReviewed();
    }

    public List<String> grounds() {
        return rendered.stream().filter(RegisterRow::isGround).map(RegisterRow::getCode).toList();
    }

    public List<String> supplementaryCodes() {
        return rendered.stream().filter(row -> !row.isGround()).map(RegisterRow::getCode).toList();
    }

    public List<String> allCodes() {
        List<String> out = new ArrayList<>(grounds());
        out.addAll(supplementaryCodes());
        return List.copyOf(out);
    }

    public List<String> timeLimitCodes() {
        return rendered.stream().filter(row -> "TIME_LIMIT".equals(row.getArgumentClass()))
                .map(RegisterRow::getCode).toList();
    }

    public CatalogueArgument argument(String code) {
        RegisterRow row = rows.get(RegisterRows.bare(code));
        if (row == null || !row.isRendered()) {
            return null;
        }
        return new CatalogueArgument(row.getCode(), row.getArgumentClass(), !row.getSupplementary(), row.getLabel(),
                row.getNominateWhen(), row.getStrongEvidence(), row.getDoesNotFit());
    }

    public CatalogueTimeLimitRule timeLimitRule(String code) {
        RegisterTimeLimitRule rule = timeLimitRules.get(RegisterRows.bare(code));
        return rule == null ? null
                : new CatalogueTimeLimitRule(rule.getCode(), rule.getRequires(), rule.getDayCount(), rule.getCompare(),
                        rule.getLimit(), rule.getLimitText());
    }

    public Map<String, CatalogueTimeLimitRule> timeLimitRules() {
        Map<String, CatalogueTimeLimitRule> out = new LinkedHashMap<>();
        timeLimitRules.keySet().forEach(code -> out.put(code, timeLimitRule(code)));
        return Map.copyOf(out);
    }

    public Map<String, String> limitTexts() {
        Map<String, String> out = new LinkedHashMap<>();
        for (RegisterTimeLimitRule rule : timeLimitRules.values()) {
            if (rule.getLimitText() != null) {
                out.put(rule.getCode(), rule.getLimitText());
            }
        }
        return Map.copyOf(out);
    }

    public List<String> recordRuleCodes() {
        return rendered.stream().filter(row -> RECORD.equals(row.getArgumentClass()))
                .map(RegisterRow::getCode).toList();
    }

    public CatalogueRecordRule recordRule(String code) {
        RegisterRecordRule rule = recordRules.get(RegisterRows.bare(code));
        return rule == null ? null
                : new CatalogueRecordRule(rule.getCode(), rule.getRuleKind(), rule.getParameters(), rule.getAmountScope(),
                        rule.getFilingSentence());
    }

    public Map<String, CatalogueRecordRule> recordRules() {
        Map<String, CatalogueRecordRule> out = new LinkedHashMap<>();
        recordRules.keySet().forEach(code -> out.put(code, recordRule(code)));
        return Map.copyOf(out);
    }

    public boolean isRecordCheck(String code) {
        RegisterRow row = rows.get(RegisterRows.bare(code));
        return row != null && row.isRendered() && RECORD.equals(row.getArgumentClass());
    }

    public String recordFilingSentence(String code, Map<String, String> figures) {
        RegisterRecordRule rule = recordRules.get(RegisterRows.bare(code));
        return rule == null ? null : filled(rule.getFilingSentence(), figures);
    }

    /** A record row's letter paragraph with the check's figures filled in; null where a figure is missing. */
    public String recordLetterParagraph(String code, Map<String, String> figures) {
        RegisterRecordRule rule = recordRules.get(RegisterRows.bare(code));
        return rule == null ? null : filled(rule.getLetterParagraph(), figures);
    }

    /** A time-limit row's letter paragraph as the register carries it, placeholders unfilled; null on other rows. */
    public String timeLimitLetterParagraph(String code) {
        RegisterTimeLimitRule rule = timeLimitRules.get(RegisterRows.bare(code));
        return rule == null ? null : rule.getLetterParagraph();
    }

    private static String filled(String sentence, Map<String, String> figures) {
        if (sentence == null) {
            return null;
        }
        String out = sentence;
        for (Map.Entry<String, String> figure : (figures == null ? Map.<String, String>of() : figures).entrySet()) {
            if (figure.getValue() != null) {
                out = out.replace("{" + figure.getKey() + "}", figure.getValue());
            }
        }
        return out.contains("{") && out.contains("}") ? null : out;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** A time-limit or record row's fixed filing sentence as the register carries it, figures unresolved. */
    public String filingSentenceTemplate(String code) {
        RegisterTimeLimitRule limit = timeLimitRules.get(RegisterRows.bare(code));
        if (limit != null) {
            return limit.getFilingSentence();
        }
        RegisterRecordRule record = recordRules.get(RegisterRows.bare(code));
        return record == null ? null : record.getFilingSentence();
    }

    public String filingSentence(String code, Long absoluteDayCount) {
        RegisterTimeLimitRule rule = timeLimitRules.get(RegisterRows.bare(code));
        if (rule == null || rule.getFilingSentence() == null || absoluteDayCount == null) {
            return null;
        }
        return rule.getFilingSentence().replace("{n}", String.valueOf(absoluteDayCount));
    }

    public RegisterRow row(String code) {
        return rows.get(RegisterRows.bare(code));
    }

    /** The bare codes of the rows carrying the role, in code order; empty where none does. */
    public List<String> rowsWithRole(String role) {
        List<String> out = new ArrayList<>();
        for (RegisterRow row : rows.values()) {
            if (role.equals(row.getRole())) {
                out.add(row.getCode());
            }
        }
        out.sort(Comparator.comparingInt(ArgumentCatalogue::number));
        return List.copyOf(out);
    }

    /** The bare code of the one row carrying the role, or null where the reason code has none. */
    public String rowWithRole(String role) {
        List<String> codes = rowsWithRole(role);
        return codes.isEmpty() ? null : codes.get(0);
    }

    public boolean isThirteenOne() {
        return THIRTEEN_ONE.equals(reasonCode());
    }

    /** A row code of any reason code in the request's shape, or of this one, as model text might carry it. */
    public Pattern rowCodeToken() {
        return Pattern.compile("(?<![0-9A-Za-z.])(?:" + REASON_CODE_SHAPE + "|" + Pattern.quote(reasonCode())
                + ")-G\\d+\\b");
    }

    private static int number(String code) {
        String digits = code.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
    }

    /** The letter's wording in the code's own response type; representment reads the base. */
    public LetterTemplates letter() {
        return letter == null ? null : letter.forResponseType(responseType());
    }

    public String qualified(String code) {
        if (code == null) {
            return null;
        }
        return code.startsWith(reasonCode() + "-") ? code : reasonCode() + "-" + code;
    }
}

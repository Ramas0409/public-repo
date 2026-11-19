package com.wp.wdp.chargeback.auto.response.service.impl;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.wp.wdp.chargeback.auto.response.config.Regions;
import com.wp.wdp.chargeback.auto.response.db.DocumentTextStore;
import com.wp.wdp.chargeback.auto.response.db.PreviousRuns;
import com.wp.wdp.chargeback.auto.response.db.RegisterRows;
import com.wp.wdp.chargeback.auto.response.db.RunStore;
import com.wp.wdp.chargeback.auto.response.dto.autocontest.AutoContestDocument;
import com.wp.wdp.chargeback.auto.response.dto.autocontest.AutoContestRequest;
import com.wp.wdp.chargeback.auto.response.dto.autocontest.AutoContestResponse;
import com.wp.wdp.chargeback.auto.response.dto.autocontest.ReferencedDocument;
import com.wp.wdp.chargeback.auto.response.dto.llm.EvidenceItem;
import com.wp.wdp.chargeback.auto.response.dto.llm.NominateChecks;
import com.wp.wdp.chargeback.auto.response.dto.llm.NominatedRow;
import com.wp.wdp.chargeback.auto.response.dto.llm.SupplementaryRow;
import com.wp.wdp.chargeback.auto.response.dto.run.RunRecord;
import com.wp.wdp.chargeback.auto.response.entity.ClassifierRunEntity;
import com.wp.wdp.chargeback.auto.response.letter.LetterFileNames;
import com.wp.wdp.chargeback.auto.response.letter.MerchantAsks;
import com.wp.wdp.chargeback.auto.response.letter.MerchantRequests;
import com.wp.wdp.chargeback.auto.response.letter.model.SummaryResult;
import com.wp.wdp.chargeback.auto.response.letter.ClassifierLetter;
import com.wp.wdp.chargeback.auto.response.letter.ExhibitPages;
import com.wp.wdp.chargeback.auto.response.letter.LetterExhibits;
import com.wp.wdp.chargeback.auto.response.letter.LetterPdf;
import com.wp.wdp.chargeback.auto.response.letter.LetterValidator;
import com.wp.wdp.chargeback.auto.response.letter.OperationsLetter;
import com.wp.wdp.chargeback.auto.response.letter.dto.LetterFinding;
import com.wp.wdp.chargeback.auto.response.letter.model.AppendedExhibits;
import com.wp.wdp.chargeback.auto.response.letter.model.AssembledLetter;
import com.wp.wdp.chargeback.auto.response.letter.model.Exhibit;
import com.wp.wdp.chargeback.auto.response.letter.model.FigureSupport;
import com.wp.wdp.chargeback.auto.response.letter.model.LetterTemplates;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFact;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFacts;
import com.wp.wdp.chargeback.auto.response.model.check.RecordChecks;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimits;
import com.wp.wdp.chargeback.auto.response.model.decision.UndecidedKind;
import com.wp.wdp.chargeback.auto.response.model.decision.ContestResult;
import com.wp.wdp.chargeback.auto.response.model.decision.Decision;
import com.wp.wdp.chargeback.auto.response.model.decision.Nomination;
import com.wp.wdp.chargeback.auto.response.model.decision.PreparedCase;
import com.wp.wdp.chargeback.auto.response.model.decision.RenderedPayload;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterRow;
import com.wp.wdp.chargeback.auto.response.questionnaire.ClassifierQuestionnaire;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireDefinition;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireResult;
import com.wp.wdp.chargeback.auto.response.service.CaseRequestMapper;
import com.wp.wdp.chargeback.auto.response.service.ClassifierContest;
import com.wp.wdp.chargeback.auto.response.service.ClassifierStage;
import com.wp.wdp.chargeback.auto.response.service.IssuerFactsReader;
import com.wp.wdp.chargeback.auto.response.service.check.ClassifierRecordRules;
import com.wp.wdp.chargeback.auto.response.service.check.ClassifierTimeLimits;
import com.wp.wdp.chargeback.auto.response.service.decision.ClassifierDecision;
import com.wp.wdp.chargeback.auto.response.service.decision.UndecidedKinds;
import com.wp.wdp.chargeback.auto.response.service.register.ArgumentCatalogue;
import com.wp.wdp.chargeback.auto.response.service.register.CataloguePayloadRenderer;
import com.wp.wdp.chargeback.auto.response.service.register.SubFormFieldLists;

/** One case end to end: the register, the issuer's answers, the classifier call, the decision, the letters and the run row. */
@Service
public class ClassifierContestImpl implements ClassifierContest {
    private static final Logger log = LoggerFactory.getLogger(ClassifierContest.class);

    protected static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final RunStore runs;
    private final RegisterRows register;
    private final IssuerFactsReader issuerFacts;
    private final ClassifierStage stage;
    private final ClassifierQuestionnaire questionnaires;
    private final OperationsLetter operationsLetters;
    private final DocumentTextStore documentTexts;
    private final ExhibitPages exhibitPages;
    private final LetterTemplates letterTemplates;
    private final ObjectMapper objectMapper;
    private final PreviousRuns previousRuns;

    public ClassifierContestImpl(RunStore runs, RegisterRows register, IssuerFactsReader issuerFacts,
                                 ClassifierStage stage, ClassifierQuestionnaire questionnaires,
                                 OperationsLetter operationsLetters, DocumentTextStore documentTexts,
                                 ExhibitPages exhibitPages, ObjectMapper objectMapper, PreviousRuns previousRuns) {
        this.runs = runs;
        this.previousRuns = previousRuns;
        this.register = register;
        this.issuerFacts = issuerFacts;
        this.stage = stage;
        this.questionnaires = questionnaires;
        this.operationsLetters = operationsLetters;
        this.documentTexts = documentTexts;
        this.exhibitPages = exhibitPages;
        this.objectMapper = objectMapper;
        this.letterTemplates = LetterTemplates.load(objectMapper);
    }

    @Override
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
        } catch (RuntimeException exception) {
            recordFailure(row, request, createdAt, exception);
            throw exception;
        }
    }

    /** The register, the region's rows, the issuer's answers, the time limits, the record checks and the rendered list. */
    protected PreparedCase prepare(CaseRow row, boolean replayOnly) {
        Long reasonCodeId = register.reasonCodeId(row.getNetwork(), row.getReasonCode());
        if (reasonCodeId == null) {
            return PreparedCase.notJudgeable(row,
                    NO_REGISTER + " (" + row.getNetwork() + " " + row.getReasonCode() + ")");
        }
        List<String> problems = new ArrayList<>();
        ArgumentCatalogue whole = ArgumentCatalogue.load(register, reasonCodeId, letterTemplates);
        String region = Regions.regionOf(row.getAcquiringPlatform());
        if (region == null) {
            problems.add("acquiring platform " + row.getAcquiringPlatform()
                    + " maps to no region; only the ALL rows were rendered");
        }
        List<String> includedRegions = Regions.includes(region);
        ArgumentCatalogue catalogue = whole.forRegions(includedRegions);
        Map<String, RegisterRow> rows = register.all(reasonCodeId);
        problems.addAll(catalogue.problems());

        IssuerFacts issuer = issuerFacts.read(row, reasonCodeId, includedRegions, replayOnly);
        log.info("case {}: issuer facts - {} replayed, {} extracted", row.getCaseNumber(),
                issuer.getReplayed(), issuer.getExtracted());

        TimeLimits limits = ClassifierTimeLimits.calculate(row, issuer, catalogue);
        RecordChecks records = ClassifierRecordRules.calculate(row, issuer, catalogue);
        RenderedPayload payload = CataloguePayloadRenderer.render(catalogue, rows,
                SubFormFieldLists.of(catalogue, QuestionnaireDefinition.load(register, reasonCodeId, row.getNetwork(),
                        catalogue.responseType())));
        payload.getMissingRows().forEach(code -> problems.add(code + " has no register row and was not rendered"));
        return new PreparedCase(row, reasonCodeId, region, catalogue, rows, issuer, limits, records, payload, problems,
                null);
    }

    /** Marks the case as a second run where its last run asked the merchant for documents and one is new since. */
    protected void withSecondRun(PreparedCase prepared, OffsetDateTime createdAt) {
        prepared.setSecondRun(previousRuns.secondRun(prepared.getRow(), createdAt));
    }

    /** Everything after the classifier call: the decision, the letters, the questionnaire and the run row, not saved. */
    protected ContestResult conclude(AutoContestRequest request, PreparedCase prepared, Nomination nomination,
                                     OffsetDateTime createdAt, String runLabel) {
        CaseRow row = prepared.getRow();
        ArgumentCatalogue catalogue = prepared.getCatalogue();
        IssuerFacts issuer = prepared.getIssuer();
        TimeLimits limits = prepared.getLimits();
        RecordChecks records = prepared.getRecords();
        List<String> problems = new ArrayList<>(prepared.getProblems());

        Decision decision = ClassifierDecision.decide(row, catalogue, nomination, limits, issuer, records);

        List<LetterFinding> findings = new ArrayList<>(letterFindings(nomination));
        AppendedExhibits filed = null;
        List<Exhibit> exhibits = List.of();
        Map<String, String> citationIds = citationIds(nomination);

        if (decision.isContest()) {
            FigureSupport support = new FigureSupport(supportedValues(row, decision, nomination, issuer),
                    documentTexts.pages(citedPages(nomination, citationIds)),
                    row.getTransactionDate() == null ? null : row.getTransactionDate().getYear());
            AssembledLetter letter = ClassifierLetter.assemble(row, catalogue, decision, limits, issuer,
                    prepared.getRows(), nomination.letter(), citationIds, Map.of(), row.getAcquirerEntity(), null,
                    records);
            findings.addAll(LetterValidator.validate(letter, decision, catalogue, limits, support, Map.of(),
                    citationIds));
            try {
                filed = fileLetter(row, letter, catalogue);
                exhibits = letter.getExhibits().getExhibits();
            } catch (RuntimeException exception) {
                log.warn("case {}: the network letter was not produced ({})", row.getCaseNumber(),
                        exception.toString());
                problems.add("the network letter was not produced");
            }
            if (filed != null) {
                problems.addAll(filed.getProblems());
            }
        }
        decision = ClassifierDecision.letterMode(decision, findings);

        String networkLetter = filed == null ? null : Base64.getEncoder().encodeToString(filed.getPdf());

        QuestionnaireResult questionnaire = questionnaires.generate(row, decision, catalogue, nomination, limits,
                records);
        boolean noQuestionnaire = questionnaire.getBody() == null
                && (decision.isContest() || decision.isCreditProcessed());
        if (noQuestionnaire) {
            problems.add("no questionnaire was built for the lead " + decision.leadCode());
        }

        boolean reviewNeeded = ClassifierDecision.CONTEST_MODERATE.equals(decision.getDecision())
                || (decision.getReviewReasons() != null && !decision.getReviewReasons().isEmpty())
                || noQuestionnaire;

        UndecidedKind kind = UndecidedKinds.of(decision, nomination);
        SummaryResult summary = operationsLetters.generate(row, decision, catalogue, nomination, limits, records,
                findings, kind, createdAt, request.getRespondBy(), !noQuestionnaire, prepared.getSecondRun());
        String opsLetter = summary.getPdf();
        if (opsLetter == null) {
            problems.add("the operations letter was not produced");
        }

        RunRecord record = recordOf(row, request, runLabel);
        record.setRegion(prepared.getRegion());
        record.setCatalogueVersion(catalogue.version());
        record.setClassifier(nomination.getStored());
        record.setClassifierOutcome(nomination.getOutcome());
        record.setClassifierError(nomination.getError());
        record.setDecision(ClassifierDecision.asMap(decision));
        record.setLetterFindings(findings);
        record.setReviewNeeded(reviewNeeded);
        record.setMerchantAsks(summary.getContent() == null ? null
                : MerchantAsks.records(summary.getContent().getAsks()));
        record.setUndecidedReason(UndecidedKinds.reason(kind,
                summary.getContent() == null ? null : summary.getContent().getBottomLine()));
        record.setProblems(problems);

        ClassifierRunEntity run = runRow(row, createdAt, decision, decision.leadCode(), nomination.getError(), record,
                opsLetter);
        run.setQuestionnaire(questionnaire.getJson());
        run.setNetworkLetter(networkLetter);

        AutoContestResponse response = response(row, decision, prepared.getRows());
        if (questionnaire.getBody() != null) {
            response.setResponseType(catalogue.responseType());
            response.setQuestionnaire(questionnaire.getBody());
        }
        if (networkLetter != null) {
            response.setNetworkLetter(networkLetter);
            response.setReferencedDocuments(referencedDocuments(request, exhibits));
        }
        response.setUndecidedReason(record.getUndecidedReason());
        response.setMerchantRequest(MerchantRequests.of(summary.getContent(), catalogue));
        return new ContestResult(row.getCaseNumber(), runLabel, decision.getDecision(), decision.getLetterMode(),
                record, List.copyOf(problems), response, opsLetter, filed == null ? null : filed.getPdf(),
                questionnaire.getJson(), run);
    }

    /** A case with no register: UNDECIDED with the reason and no call, its run row not saved. */
    protected ContestResult notJudgeable(AutoContestRequest request, PreparedCase prepared, OffsetDateTime createdAt,
                                         String runLabel) {
        CaseRow row = prepared.getRow();
        String error = prepared.getError();
        Decision decision = new Decision(ClassifierDecision.UNDECIDED, ClassifierDecision.NO_LETTER,
                ClassifierDecision.amounts(row, null, null), null, List.of(), List.of(error));
        SummaryResult summary = operationsLetters.generate(row, decision, null, null, null, null, List.of(),
                UndecidedKind.NO_REGISTER, createdAt, request.getRespondBy(), true, null);
        String opsLetter = summary.getPdf();
        RunRecord record = recordOf(row, request, runLabel);
        record.setClassifierOutcome(ClassifierStage.ERROR);
        record.setClassifierError(error);
        record.setDecision(ClassifierDecision.asMap(decision));
        record.setLetterFindings(List.of());
        record.setReviewNeeded(true);
        record.setUndecidedReason(UndecidedKinds.reason(UndecidedKind.NO_REGISTER,
                summary.getContent() == null ? null : summary.getContent().getBottomLine()));
        record.setProblems(List.of(error));
        log.warn("case {}: {}", row.getCaseNumber(), error);
        return new ContestResult(row.getCaseNumber(), runLabel, decision.getDecision(), decision.getLetterMode(),
                record, List.of(error), undecided(response(row, decision, Map.of()), record), opsLetter, null, null,
                runRow(row, createdAt, decision, null, error, record, opsLetter));
    }

    /** Writes the run's row and gives its id to the response, and the network letter its name for that run. */
    protected void save(ContestResult result, PreparedCase prepared) {
        AutoContestResponse response = result.getResponse();
        response.setRunId(runs.recordRun(result.getRun()));
        if (response.getNetworkLetter() != null) {
            response.setNetworkLetterName(LetterFileNames.networkResponse(response.getCaseNumber(),
                    response.getActionSequence(), response.getRunId()));
        }
    }

    /** The run record as its row holds it. */
    protected String pretty(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("could not write the run record: " + exception.getMessage(), exception);
        }
    }

    private ClassifierRunEntity runRow(CaseRow row, OffsetDateTime createdAt, Decision decision, String leadCode,
                                       String error, RunRecord record, String opsLetter) {
        ClassifierRunEntity run = new ClassifierRunEntity();
        run.setPlatform(row.getAcquiringPlatform());
        run.setCaseNumber(row.getCaseNumber());
        run.setActionSequence(row.getActionSequence());
        run.setCreatedAt(createdAt);
        run.setDecision(decision.getDecision());
        run.setLeadCode(leadCode);
        run.setDisputedAmount(decision.getAmounts().getDisputed());
        if (!ClassifierDecision.UNDECIDED.equals(decision.getDecision())) {
            run.setContestedAmount(decision.getAmounts().getContested());
        }
        run.setCurrency(decision.getAmounts().getDisputedCurrency());
        run.setError(error);
        run.setRunRecord(pretty(record));
        run.setOpsLetter(opsLetter);
        return run;
    }

    private static RunRecord recordOf(CaseRow row, AutoContestRequest request, String runLabel) {
        RunRecord record = new RunRecord();
        record.setCaseNumber(row.getCaseNumber());
        record.setActionSequence(row.getActionSequence());
        record.setRun(runLabel);
        record.setRequest(request);
        return record;
    }

    private void recordFailure(CaseRow row, AutoContestRequest request, OffsetDateTime createdAt,
                               RuntimeException exception) {
        try {
            ClassifierRunEntity run = new ClassifierRunEntity();
            run.setPlatform(row.getAcquiringPlatform());
            run.setCaseNumber(row.getCaseNumber());
            run.setActionSequence(row.getActionSequence());
            run.setCreatedAt(createdAt);
            run.setDisputedAmount(row.getDisputedAmount());
            run.setCurrency(row.getDisputeCurrency());
            run.setError(exception.toString());
            run.setOpsLetter(operationsLetters.generate(row, null, null, null, null, null, List.of(),
                    UndecidedKinds.ofFailure(exception), createdAt, request.getRespondBy(), true, null).getPdf());
            runs.recordRun(run);
        } catch (RuntimeException recording) {
            log.warn("case {}: the failed run could not be recorded ({})", row.getCaseNumber(), recording.toString());
        }
    }

    static AutoContestResponse response(CaseRow row, Decision decision, Map<String, RegisterRow> rows) {
        AutoContestResponse out = new AutoContestResponse();
        out.setCaseNumber(row.getCaseNumber());
        out.setActionSequence(row.getActionSequence());
        out.setReasonCode(row.getReasonCode());
        out.setDecision(decision.getDecision());
        String lead = decision.leadCode();
        RegisterRow leadRow = lead == null ? null : rows.get(bareCode(lead));
        out.setResponseReason(leadRow == null ? null : leadRow.getResponseReason());
        out.setCurrency(decision.getAmounts().getDisputedCurrency());
        out.setDisputedAmount(decision.getAmounts().getDisputed());
        if (!ClassifierDecision.UNDECIDED.equals(decision.getDecision())) {
            out.setContestedAmount(decision.getAmounts().getContested());
            out.setAcceptedAmount(decision.getAmounts().getAccepted());
        }
        return out;
    }

    private static AutoContestResponse undecided(AutoContestResponse response, RunRecord record) {
        response.setUndecidedReason(record.getUndecidedReason());
        return response;
    }

    static List<ReferencedDocument> referencedDocuments(AutoContestRequest request, List<Exhibit> exhibits) {
        Map<String, String> names = new LinkedHashMap<>();
        for (AutoContestDocument document : request.getDocuments()) {
            names.put(document.getS3Key(), document.getName());
        }
        List<ReferencedDocument> out = new ArrayList<>();
        for (Exhibit exhibit : exhibits) {
            out.add(new ReferencedDocument(exhibit.getRef(), names.get(exhibit.getRef())));
        }
        return out;
    }

    private AppendedExhibits fileLetter(CaseRow row, AssembledLetter letter, ArgumentCatalogue catalogue) {
        byte[] rendered = LetterPdf.render(LetterPdf.document(letter.getTitle(), letter.getHtml()));
        return exhibitPages.append(row, rendered, letter.getExhibits().getExhibits(), letter.getNamed(),
                catalogue.letter(), letter.getFooter(), letter.getTitle());
    }

    static String bareCode(String code) {
        int dash = code.lastIndexOf('-');
        return dash < 0 ? code : code.substring(dash + 1);
    }

    /** Every page the model's answer cites, by the document the citation id names. */
    static Map<String, Set<Integer>> citedPages(Nomination nomination, Map<String, String> citationIds) {
        Map<String, Set<Integer>> out = new LinkedHashMap<>();
        String answer = nomination.getStored() == null ? "" : nomination.getStored().toString();
        Matcher matcher = LetterExhibits.CITATION.matcher(answer);
        while (matcher.find()) {
            String ref = citationIds.get(matcher.group(1).toUpperCase(Locale.ROOT));
            if (ref != null) {
                out.computeIfAbsent(ref, key -> new LinkedHashSet<>()).add(Integer.parseInt(matcher.group(2)));
            }
        }
        return out;
    }

    static Map<String, String> citationIds(Nomination nomination) {
        Map<String, String> ids = nomination.getResponse() == null ? null
                : nomination.getResponse().getCitationIds();
        if (ids == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        ids.forEach((citationKey, citationValue) -> out.put(citationKey.toUpperCase(Locale.ROOT), citationValue));
        return Map.copyOf(out);
    }

    static List<LetterFinding> letterFindings(Nomination nomination) {
        NominateChecks checks = nomination.getResponse() == null ? null
                : nomination.getResponse().getChecks();
        List<LetterFinding> findings = checks == null ? null : checks.getLetterFindings();
        return findings == null ? List.of() : findings;
    }

    static List<String> supportedValues(CaseRow row, Decision decision, Nomination nomination, IssuerFacts issuer) {
        List<String> out = new ArrayList<>();
        if (issuer != null && issuer.getFacts() != null) {
            for (IssuerFact fact : issuer.getFacts()) {
                if (fact != null && fact.getValue() != null) {
                    out.add(fact.getValue());
                }
            }
        }
        out.add(String.valueOf(decision.getAmounts().getDisputed()));
        out.add(String.valueOf(decision.getAmounts().getContested()));
        if (row.getTransactionAmount() != null) {
            out.add(row.getTransactionAmount().toPlainString());
        }
        for (Object date : List.of(String.valueOf(row.getTransactionDate()),
                String.valueOf(row.getProcessingDate()), String.valueOf(row.getDisputeReportedDate()))) {
            out.add(String.valueOf(date));
        }
        out.add(ClassifierLetter.longDate(row.getTransactionDate()));
        out.add(ClassifierLetter.longDate(row.getProcessingDate()));
        out.add(ClassifierLetter.longDate(row.getDisputeReportedDate()));
        if (nomination.getChecked() != null) {
            List<List<EvidenceItem>> cited = new ArrayList<>();
            for (NominatedRow entry : nomination.getChecked().getNominated()) {
                cited.add(entry.getEvidence());
            }
            for (SupplementaryRow entry : nomination.getChecked().getSupplementary()) {
                cited.add(entry.getEvidence());
            }
            for (List<EvidenceItem> evidence : cited) {
                for (EvidenceItem item : evidence == null ? List.<EvidenceItem>of() : evidence) {
                    if (item.getQuote() != null) {
                        out.add(item.getQuote());
                    }
                }
            }
        }
        return out;
    }
}

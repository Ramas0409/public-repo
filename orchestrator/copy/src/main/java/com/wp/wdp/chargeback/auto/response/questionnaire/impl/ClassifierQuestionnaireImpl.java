package com.wp.wdp.chargeback.auto.response.questionnaire.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.wp.wdp.chargeback.auto.response.db.RegisterRows;
import com.wp.wdp.chargeback.auto.response.dto.llm.NominatedRow;
import com.wp.wdp.chargeback.auto.response.dto.llm.SubFormValue;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.check.RecordCheckRow;
import com.wp.wdp.chargeback.auto.response.model.check.RecordChecks;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimits;
import com.wp.wdp.chargeback.auto.response.model.decision.Decision;
import com.wp.wdp.chargeback.auto.response.model.decision.Nomination;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterRow;
import com.wp.wdp.chargeback.auto.response.questionnaire.ClassifierQuestionnaire;
import com.wp.wdp.chargeback.auto.response.questionnaire.QuestionnaireArgumentText;
import com.wp.wdp.chargeback.auto.response.questionnaire.QuestionnaireBody;
import com.wp.wdp.chargeback.auto.response.questionnaire.QuestionnaireFieldResolvers;
import com.wp.wdp.chargeback.auto.response.questionnaire.dto.QuestionnaireRequest;
import com.wp.wdp.chargeback.auto.response.questionnaire.dto.VisaAllocationPreArbRequest;
import com.wp.wdp.chargeback.auto.response.questionnaire.dto.VisaQstnnairCollabReprRequest;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.FilledField;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireDefinition;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireField;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnairePath;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireResult;
import com.wp.wdp.chargeback.auto.response.service.register.ArgumentCatalogue;
import com.wp.wdp.chargeback.auto.response.util.LogSafe;

@Component
public class ClassifierQuestionnaireImpl implements ClassifierQuestionnaire {
    private static final Logger log = LoggerFactory.getLogger(ClassifierQuestionnaire.class);
    private static final String IN_ADDITION = "In addition,";
    private static final String CTM_TEMPLATE = "DUMMY_CTM_TEMPLATE";
    private static final Map<String, Class<? extends QuestionnaireRequest>> BODIES = Map.of(
            QuestionnaireDefinition.REPRESENTMENT, VisaQstnnairCollabReprRequest.class,
            QuestionnaireDefinition.ALLOCATION_PREARBITRATION, VisaAllocationPreArbRequest.class);

    private final ObjectMapper objectMapper;
    private final ObjectMapper strictMapper;
    private final RegisterRows register;

    public ClassifierQuestionnaireImpl(ObjectMapper objectMapper, RegisterRows register) {
        this.objectMapper = objectMapper;
        this.strictMapper = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.register = register;
    }

    @Override
    public QuestionnaireResult generate(CaseRow row, Decision decision, ArgumentCatalogue catalogue,
                                        Nomination nomination, TimeLimits limits, RecordChecks records) {
        if (!decision.isContest() && !decision.isCreditProcessed()) {
            return new QuestionnaireResult(null, null);
        }
        String leadCode = decision.leadCode();

        RegisterRow lead = leadCode == null ? null : catalogue.row(leadCode);
        String responseType = catalogue.responseType();
        Class<? extends QuestionnaireRequest> bodyClass = BODIES.get(responseType);
        if (bodyClass == null) {
            log.warn("event=questionnaire.none reason=no-body responseType={}", responseType);
            return new QuestionnaireResult(null, null);
        }
        QuestionnaireDefinition definition = QuestionnaireDefinition.load(register,
                register.reasonCodeId(row.getNetwork(), row.getReasonCode()), row.getNetwork(), responseType);
        QuestionnairePath baseForm = lead == null || lead.getResponseReason() == null ? null
                : definition.baseForm(lead.getResponseReason());
        QuestionnairePath subForm = baseForm == null || baseForm.getSubFormField() == null
                || lead.getSubFormCode() == null ? null : definition.subForm(baseForm, lead.getSubFormCode());
        if (baseForm == null || (baseForm.getSubFormField() != null && subForm == null)) {
            log.warn("event=questionnaire.none reason=no-form lead={}", leadCode);
            return new QuestionnaireResult(null, null);
        }

        QuestionnaireFieldResolvers resolvers = new QuestionnaireFieldResolvers(decision, row, baseForm, subForm,
                QuestionnaireArgumentText.of(decision, catalogue, nomination, limits, records, row),
                QuestionnaireArgumentText.leadNote(decision, nomination),
                reductionSentence(decision, catalogue, records), CTM_TEMPLATE, responseType,
                structuredValues(leadCode, nomination), definition.getOptions());
        List<FilledField> fields = fill(baseForm, subForm, resolvers);

        QuestionnaireRequest body = strictMapper.convertValue(QuestionnaireBody.shape(fields), bodyClass);
        return new QuestionnaireResult(body, json(body));
    }

    private String json(QuestionnaireRequest body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            log.warn("event=questionnaire.failed error={}", exception.getClass().getSimpleName(), LogSafe.of(exception));
            return null;
        }
    }

    /** Every field of the base form, then of the sub-form, with the value its resolver gives it. */
    static List<FilledField> fill(QuestionnairePath baseForm, QuestionnairePath subForm,
                                  QuestionnaireFieldResolvers resolvers) {
        List<QuestionnaireField> all = new ArrayList<>(baseForm.getFields());
        if (subForm != null) {
            all.addAll(subForm.getFields());
        }
        List<FilledField> out = new ArrayList<>();
        for (QuestionnaireField field : all) {
            Integer items = QuestionnaireBody.listItems(field.getName());
            if (items == null) {
                out.add(new FilledField(field, resolvers.value(field, field.getName())));
                continue;
            }
            for (int index = 0; index < items; index++) {
                String name = field.getName().replaceFirst("\\[]", "[" + index + "]");
                QuestionnaireField item = new QuestionnaireField(name, field.getLabel(), field.getDataType(),
                        field.getMaxLength(), field.getRequiredWhen(), field.getDisplayOrder());
                out.add(new FilledField(item, resolvers.value(field, name)));
            }
        }
        return out;
    }

    /** The lead row's structured answer, where the lead is a nominated row that gave one. */
    static Map<String, SubFormValue> structuredValues(String leadCode, Nomination nomination) {
        if (leadCode == null || nomination == null || nomination.getChecked() == null) {
            return null;
        }
        for (NominatedRow nominated : nomination.getChecked().getNominated()) {
            if (RegisterRows.bare(leadCode).equals(RegisterRows.bare(nominated.getCode()))) {
                return nominated.getSubFormValues();
            }
        }
        return null;
    }

    /** The register sentence of the record row that reduced the contested amount, where one did. */
    private static String reductionSentence(Decision decision, ArgumentCatalogue catalogue, RecordChecks records) {
        String leadCode = decision.leadCode();
        RecordCheckRow check = leadCode == null || records == null ? null : records.row(RegisterRows.bare(leadCode));
        if (check == null) {
            return null;
        }
        String sentence = catalogue.recordFilingSentence(RegisterRows.bare(leadCode), check.getFigures());
        if (sentence == null) {
            return null;
        }
        String text = sentence.strip();
        if (text.regionMatches(true, 0, IN_ADDITION, 0, IN_ADDITION.length())) {
            text = text.substring(IN_ADDITION.length()).stripLeading();
            text = text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        return text;
    }
}

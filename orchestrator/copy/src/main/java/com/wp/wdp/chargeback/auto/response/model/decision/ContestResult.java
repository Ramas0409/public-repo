package com.wp.wdp.chargeback.auto.response.model.decision;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import com.wp.wdp.chargeback.auto.response.dto.autocontest.AutoContestResponse;
import com.wp.wdp.chargeback.auto.response.dto.run.RunRecord;
import com.wp.wdp.chargeback.auto.response.entity.ClassifierRunEntity;

@Getter
@RequiredArgsConstructor
public final class ContestResult {
    private final String caseNumber;
    private final String runLabel;
    private final String decision;
    private final String letterMode;
    private final RunRecord record;
    private final List<String> problems;
    private final AutoContestResponse response;
    private final String opsLetter;
    private final byte[] letter;
    private final String questionnaire;
    private final ClassifierRunEntity run;
    @Setter
    private long lettersMillis;
    @Setter
    private long questionnaireMillis;

    public ContestResult(String caseNumber, String runLabel, String decision, String letterMode, RunRecord record, List<String> problems, AutoContestResponse response, String opsLetter, byte[] letter, String questionnaire) {
        this(caseNumber, runLabel, decision, letterMode, record, problems, response, opsLetter, letter, questionnaire, null);
    }

    public ContestResult(String caseNumber, String runLabel, String decision, String letterMode, RunRecord record, List<String> problems, AutoContestResponse response, String opsLetter, byte[] letter) {
        this(caseNumber, runLabel, decision, letterMode, record, problems, response, opsLetter, letter, null, null);
    }

    public ContestResult(String caseNumber, String runLabel, String decision, String letterMode, RunRecord record, List<String> problems, AutoContestResponse response, String opsLetter) {
        this(caseNumber, runLabel, decision, letterMode, record, problems, response, opsLetter, null, null, null);
    }

    public ContestResult(String caseNumber, String runLabel, String decision, String letterMode, RunRecord record, List<String> problems, AutoContestResponse response) {
        this(caseNumber, runLabel, decision, letterMode, record, problems, response, null, null, null, null);
    }
}

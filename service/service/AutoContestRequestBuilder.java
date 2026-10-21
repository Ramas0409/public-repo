package com.wp.wdp.auto.response.event.service.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.wp.wdp.auto.response.event.service.dto.AutoContestCredit;
import com.wp.wdp.auto.response.event.service.dto.AutoContestDispute;
import com.wp.wdp.auto.response.event.service.dto.AutoContestDocument;
import com.wp.wdp.auto.response.event.service.dto.AutoContestMerchant;
import com.wp.wdp.auto.response.event.service.dto.AutoContestRequest;
import com.wp.wdp.auto.response.event.service.dto.AutoContestReversal;
import com.wp.wdp.auto.response.event.service.dto.AutoContestTransaction;
import com.wp.wdp.auto.response.event.service.model.CaseCredit;
import com.wp.wdp.auto.response.event.service.model.CaseDetails;
import com.wp.wdp.auto.response.event.service.model.CaseDocument;
import com.wp.wdp.auto.response.event.service.model.EventCodes;
import com.wp.wdp.auto.response.event.service.model.Mode;

@Component
public class AutoContestRequestBuilder {

    public AutoContestRequest build(CaseDetails details, String platform, Mode mode) {
        return build(details, platform, mode.name());
    }

    public AutoContestRequest build(CaseDetails details, String platform, String mode) {
        AutoContestRequest request = new AutoContestRequest();
        request.setCaseNumber(details.getCaseNumber());
        request.setActionSequence(details.getActionSequence());
        request.setPlatform(platform);
        request.setNetwork(details.getNetwork());
        request.setReasonCode(details.getReasonCode());
        request.setMode(mode);
        request.setNetworkCaseNumber(details.getNetworkCaseNumber());
        request.setArn(details.getArn());
        request.setAcquirerName(EventCodes.ACQUIRER_NAME);
        request.setRespondBy(details.getResponseDueDate());
        request.setMerchant(new AutoContestMerchant(null, details.getMerchantDescriptor(),
                details.getMerchantCountry()));
        request.setTransaction(transaction(details));
        request.setDispute(new AutoContestDispute(details.getDisputeReportedDate(), details.getDisputedAmount(),
                details.getDisputeCurrency()));
        request.setDocuments(documents(details.getDocuments()));
        return request;
    }

    private AutoContestTransaction transaction(CaseDetails details) {
        AutoContestTransaction transaction = new AutoContestTransaction();
        transaction.setDate(details.getTransactionDate());
        transaction.setProcessingDate(details.getProcessingDate());
        transaction.setAmount(details.getTransactionAmount());
        transaction.setCurrency(details.getPurchaseCurrency());
        transaction.setAuthorisationCode(details.getAuthorisationCode());
        transaction.setMcc(details.getMcc());
        transaction.setCashBackAmount(details.getCashBackAmount());
        transaction.setCardLastFour(details.getCardLastFour());
        transaction.setMotoIndicator(details.getMotoIndicator());
        transaction.setEntryMode(details.getEntryMode());
        transaction.setCardProductType(details.getCardProductType());
        transaction.setCredits(credits(details.getCredits()));
        transaction.setReversals(reversals(details.getReversals()));
        return transaction;
    }

    private List<AutoContestCredit> credits(List<CaseCredit> caseCredits) {
        if (caseCredits == null) {
            return null;
        }
        List<AutoContestCredit> credits = new ArrayList<>();
        for (CaseCredit credit : caseCredits) {
            credits.add(new AutoContestCredit(credit.getArn(), credit.getDate(), credit.getAmount(),
                    credit.getCurrency()));
        }
        return credits;
    }

    private List<AutoContestReversal> reversals(List<CaseCredit> caseReversals) {
        if (caseReversals == null) {
            return null;
        }
        List<AutoContestReversal> reversals = new ArrayList<>();
        for (CaseCredit reversal : caseReversals) {
            reversals.add(new AutoContestReversal(reversal.getArn(), reversal.getDate(), reversal.getAmount(),
                    reversal.getCurrency()));
        }
        return reversals;
    }

    private List<AutoContestDocument> documents(List<CaseDocument> caseDocuments) {
        List<AutoContestDocument> documents = new ArrayList<>();
        if (caseDocuments != null) {
            for (CaseDocument document : caseDocuments) {
                documents.add(new AutoContestDocument(document.getS3Key(), document.getSide()));
            }
        }
        return documents;
    }
}

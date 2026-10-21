package com.wp.wdp.auto.response.event.service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wp.wdp.auto.response.event.service.entity.ActionEntity;
import com.wp.wdp.auto.response.event.service.entity.CaseEntity;
import com.wp.wdp.auto.response.event.service.model.CaseDetails;
import com.wp.wdp.auto.response.event.service.repository.ActionRepository;
import com.wp.wdp.auto.response.event.service.repository.CaseRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CaseRecordReader {

    public static final int DEFAULT_CURRENCY_EXPONENT = 2;

    private final CaseRepository cases;
    private final ActionRepository actions;

    @Transactional(readOnly = true)
    public Optional<CaseDetails> findCase(String caseNumber, String actionSequence) {
        Optional<CaseEntity> found = cases.findByCaseNumber(caseNumber);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return actions.findByCaseNumberAndActionSequence(caseNumber, actionSequence)
                .map(action -> caseDetails(found.get(), action));
    }

    @Transactional(readOnly = true)
    public Map<String, String> reasonCodes(Set<String> caseNumbers) {
        Map<String, String> reasonCodes = new HashMap<>();
        for (ActionEntity action : actions.findFirstActionsWithReason(caseNumbers)) {
            reasonCodes.put(action.getCaseNumber(), action.getReasonCode());
        }
        return reasonCodes;
    }

    private CaseDetails caseDetails(CaseEntity record, ActionEntity action) {
        CaseDetails details = new CaseDetails();
        details.setCaseNumber(record.getCaseNumber());
        details.setActionSequence(action.getActionSequence());
        details.setNetwork(record.getNetwork());
        details.setReasonCode(action.getReasonCode());
        details.setActionStatus(action.getActionStatus());
        details.setResponseDueDate(isoDate(action.getResponseDueDate()));
        details.setNetworkCaseNumber(record.getNetworkCaseNumber());
        details.setArn(record.getArn());
        details.setMerchantDescriptor(record.getMerchantDescriptor());
        details.setMerchantCountry(record.getMerchantCountry());
        details.setTransactionDate(isoDate(record.getTransactionDate()));
        details.setProcessingDate(isoDate(record.getProcessingDate()));
        details.setTransactionAmount(scaled(record.getPurchaseAmount(), record.getPurchaseAmountExponent()));
        details.setPurchaseCurrency(record.getPurchaseCurrency());
        details.setAuthorisationCode(record.getAuthorisationCode());
        details.setMcc(record.getMcc());
        details.setCashBackAmount(scaled(record.getCashBackAmount(), record.getPurchaseAmountExponent()));
        details.setCardLastFour(record.getCardLastFour());
        details.setMotoIndicator(record.getMotoIndicator());
        details.setEntryMode(record.getEntryMode());
        details.setDisputeReportedDate(isoDate(action.getReportedDate()));
        details.setDisputedAmount(scaled(action.getDisputeAmount(), action.getDisputeCurrencyExponent()));
        details.setDisputeCurrency(action.getDisputeCurrency());
        return details;
    }

    private BigDecimal scaled(BigDecimal amount, Integer exponent) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(exponent == null ? DEFAULT_CURRENCY_EXPONENT : exponent, RoundingMode.UNNECESSARY);
    }

    private String isoDate(LocalDate date) {
        return date == null ? null : date.toString();
    }
}

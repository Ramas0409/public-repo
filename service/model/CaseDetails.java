package com.wp.wdp.auto.response.event.service.model;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CaseDetails {

    private String caseNumber;
    private String actionSequence;
    private String network;
    private String reasonCode;
    private String actionStatus;
    private String responseDueDate;
    private String networkCaseNumber;
    private String arn;
    private String merchantDescriptor;
    private String merchantCountry;
    private String transactionDate;
    private String processingDate;
    private BigDecimal transactionAmount;
    private String purchaseCurrency;
    private String authorisationCode;
    private String mcc;
    private BigDecimal cashBackAmount;
    private String cardLastFour;
    private String motoIndicator;
    private String entryMode;
    private String disputeReportedDate;
    private BigDecimal disputedAmount;
    private String disputeCurrency;
    private List<CaseDocument> documents;
    private String cardProductType;
    private List<CaseCredit> credits;
    private List<CaseCredit> reversals;
}

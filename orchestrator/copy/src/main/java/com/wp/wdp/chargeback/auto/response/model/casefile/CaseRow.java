package com.wp.wdp.chargeback.auto.response.model.casefile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The case as the run reads it, built from the request and nowhere else. */
@Getter
@RequiredArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"caseNumber", "actionSequence", "arn", "networkCaseNumber", "cardLastFour", "transactionDate", "processingDate", "disputeReportedDate", "transactionAmount", "purchaseCurrency", "disputedAmount", "disputeCurrency", "authorisationCode", "mcc", "acquiringPlatform", "merchantDescriptor", "merchantName", "acquirerEntity", "issuerName", "cashBackAmount", "merchantCountry", "motoIndicator", "entryMode", "cardProductType", "credits", "reversals", "documents", "network", "reasonCode"})
public final class CaseRow {
    public static final String UNKNOWN_DOCUMENT = "doc#?";

    private final String caseNumber;
    private final String actionSequence;
    private final String network;
    private final String reasonCode;
    private final String arn;
    private final String networkCaseNumber;
    private final String cardLastFour;
    private final LocalDate transactionDate;
    private final LocalDate processingDate;
    private final LocalDate disputeReportedDate;
    private final BigDecimal transactionAmount;
    private final String purchaseCurrency;
    private final BigDecimal disputedAmount;
    private final String disputeCurrency;
    private final String authorisationCode;
    private final String mcc;
    private final String acquiringPlatform;
    private final String merchantDescriptor;
    private final String merchantName;
    private final String acquirerEntity;
    private final String issuerName;
    private final BigDecimal cashBackAmount;
    private final String merchantCountry;
    private final String motoIndicator;
    private final String entryMode;
    private final String cardProductType;
    private final List<CaseCredit> credits;
    private final List<CaseCredit> reversals;
    private final List<CaseDocument> documents;

    public CaseRow(String caseNumber, String actionSequence, String arn, String networkCaseNumber, String cardLastFour, LocalDate transactionDate, LocalDate processingDate, LocalDate disputeReportedDate, BigDecimal transactionAmount, String purchaseCurrency, BigDecimal disputedAmount, String disputeCurrency, String authorisationCode, String mcc, String acquiringPlatform, String merchantDescriptor, String merchantName, String acquirerEntity, String issuerName, BigDecimal cashBackAmount, String merchantCountry, String motoIndicator, String entryMode, List<CaseDocument> documents) {
        this(caseNumber, actionSequence, "VISA", "13.1", arn, networkCaseNumber, cardLastFour,
                transactionDate, processingDate, disputeReportedDate, transactionAmount, purchaseCurrency,
                disputedAmount, disputeCurrency, authorisationCode, mcc, acquiringPlatform, merchantDescriptor,
                merchantName, acquirerEntity, issuerName, cashBackAmount, merchantCountry, motoIndicator, entryMode,
                null, null, null, documents);
    }

    public List<CaseDocument> issuerDocuments() {
        return documents.stream().filter(document -> "ISSUER".equals(document.getDeclaredSide())).toList();
    }

    public List<CaseDocument> merchantDocuments() {
        return documents.stream().filter(document -> "MERCHANT".equals(document.getDeclaredSide())).toList();
    }

    /** A document as a log line names it: its side and its place among the case's documents of that side, from 1. */
    public String documentName(String ref) {
        CaseDocument named = null;
        for (CaseDocument document : documents) {
            if (document.getRef().equals(ref)) {
                named = document;
                break;
            }
        }
        if (named == null) {
            return UNKNOWN_DOCUMENT;
        }
        String side = sideOf(named);
        int place = 0;
        for (CaseDocument document : documents) {
            if (side.equals(sideOf(document))) {
                place++;
                if (document == named) {
                    break;
                }
            }
        }
        return side.toLowerCase() + "#" + place;
    }

    private static String sideOf(CaseDocument document) {
        return document.getDeclaredSide() == null ? "" : document.getDeclaredSide();
    }
}

package com.wp.wdp.auto.response.event.service.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Immutable
@Table(schema = "wdp", name = "\"case\"")
public class CaseEntity {

    @Id
    @Column(name = "i_case_id")
    private Long id;
    @Column(name = "i_case")
    private String caseNumber;
    @Column(name = "c_case_ntwk")
    private String network;
    @Column(name = "c_ntwk_case_id")
    private String networkCaseNumber;
    @Column(name = "i_acq_refnce_num")
    private String arn;
    @Column(name = "c_merchant_name")
    private String merchantDescriptor;
    @Column(name = "c_cntry")
    private String merchantCountry;
    @Column(name = "d_tr")
    private LocalDate transactionDate;
    @Column(name = "d_prcss_tr")
    private LocalDate processingDate;
    @Column(name = "a_purchase_amt")
    private BigDecimal purchaseAmount;
    @Column(name = "c_purchase_currency")
    private String purchaseCurrency;
    @Column(name = "i_purchase_amt_exponent")
    private Integer purchaseAmountExponent;
    @Column(name = "c_auth_tr")
    private String authorisationCode;
    @Column(name = "c_mcc")
    private String mcc;
    @Column(name = "a_csh_back")
    private BigDecimal cashBackAmount;
    @Column(name = "i_acct_cdh_lst")
    private String cardLastFour;
    @Column(name = "x_moto")
    private String motoIndicator;
    @Column(name = "c_entry_mode")
    private String entryMode;
}

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
@Table(schema = "wdp", name = "action")
public class ActionEntity {

    @Id
    @Column(name = "i_action_id")
    private Long id;
    @Column(name = "i_case")
    private String caseNumber;
    @Column(name = "i_action_seq")
    private String actionSequence;
    @Column(name = "c_reason")
    private String reasonCode;
    @Column(name = "c_action_sta")
    private String actionStatus;
    @Column(name = "d_response_due")
    private LocalDate responseDueDate;
    @Column(name = "d_action_reported")
    private LocalDate reportedDate;
    @Column(name = "a_dispute_amt")
    private BigDecimal disputeAmount;
    @Column(name = "c_dispute_currency")
    private String disputeCurrency;
    @Column(name = "i_dispute_currency_exponent")
    private Integer disputeCurrencyExponent;
}

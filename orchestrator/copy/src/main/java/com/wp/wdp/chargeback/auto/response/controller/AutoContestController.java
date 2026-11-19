package com.wp.wdp.chargeback.auto.response.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.wp.wdp.chargeback.auto.response.dto.autocontest.AutoContestRequest;
import com.wp.wdp.chargeback.auto.response.dto.autocontest.AutoContestResponse;
import com.wp.wdp.chargeback.auto.response.service.ClassifierContest;
import com.wp.wdp.chargeback.auto.response.service.EnabledReasonCodes;
import com.wp.wdp.chargeback.auto.response.util.LogContext;

/**
 * {@code POST /auto-contest} - one case through the classifier pipeline, the whole case in the body
 *: the orchestrator looks nothing up on a platform. The request's constraints (the {@code dto}
 * annotations) are checked before this method runs; every violation is one 400 listing them all, answered
 * by {@link com.wp.wdp.chargeback.auto.response.exception.GlobalExceptionHandler}. Every run writes its classifier_run row; there is no dry-run
 * switch. The run's record carries the request as the contract holds it. A network and reason code the register does not
 * enable is a 400 "is not enabled" before any work.
 */
@RequiredArgsConstructor
@RestController
public class AutoContestController {

    private final ClassifierContest contest;
    private final EnabledReasonCodes enabledReasonCodes;

    @PostMapping("/auto-contest")
    public ResponseEntity<AutoContestResponse> contest(@Valid @RequestBody AutoContestRequest request) {
        LogContext.putCase(request.getPlatform(), request.getCaseNumber(), request.getActionSequence());
        enabledReasonCodes.require(request.getNetwork(), request.getReasonCode());
        return ResponseEntity.ok(contest.contest(request).getResponse());
    }
}

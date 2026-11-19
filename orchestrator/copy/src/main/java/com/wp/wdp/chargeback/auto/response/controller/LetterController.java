package com.wp.wdp.chargeback.auto.response.controller;

import java.util.Base64;

import com.wp.wdp.chargeback.auto.response.letter.model.StoredLetter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;
import com.wp.wdp.chargeback.auto.response.dto.run.LetterRequest;
import com.wp.wdp.chargeback.auto.response.service.NetworkLetterService;
import com.wp.wdp.chargeback.auto.response.service.OperationsLetterService;
import com.wp.wdp.chargeback.auto.response.util.LogContext;

/**
 * The letters kept on a case's run, each as the PDF itself: {@code GET /auto-contest/network-letter}, the
 * representment filed with the network, for any client that fetches it after the run, and
 * {@code GET /auto-contest/ops-letter}, the AI decision summary, for the page that shows it; each is named for the run
 * served. The query names the case by {@code platform}, {@code caseNumber} and {@code actionSequence}; {@code runId} is optional and names the run,
 * which must be the case's; without it the case's latest run is answered. A parameter that is missing or invalid is a
 * 400 listing every problem, no matching run and a run with no letter are each a 404, all in the WDP error shape.
 * Each reads one row and writes nothing.
 */
@RequiredArgsConstructor
@RestController
public class LetterController {

    private final NetworkLetterService networkLetters;
    private final OperationsLetterService operationsLetters;

    @GetMapping("/auto-contest/network-letter")
    public ResponseEntity<byte[]> networkLetter(@Valid @ModelAttribute LetterRequest request) {
        LogContext.putCase(request.getPlatform(), request.getCaseNumber(), request.getActionSequence());
        LogContext.putRunId(request.getRunId());
        StoredLetter letter = networkLetters.letter(request);
        return pdf(letter, networkLetters.fileName(request, letter.getRunId()));
    }

    @GetMapping("/auto-contest/ops-letter")
    public ResponseEntity<byte[]> opsLetter(@Valid @ModelAttribute LetterRequest request) {
        LogContext.putCase(request.getPlatform(), request.getCaseNumber(), request.getActionSequence());
        LogContext.putRunId(request.getRunId());
        StoredLetter letter = operationsLetters.letter(request);
        return pdf(letter, operationsLetters.fileName(request, letter.getRunId()));
    }

    private static ResponseEntity<byte[]> pdf(StoredLetter letter, String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .body(Base64.getDecoder().decode(letter.getLetter()));
    }
}

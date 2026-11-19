package com.wp.wdp.chargeback.auto.response.model.casefile;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import com.wp.wdp.chargeback.auto.response.service.Cases;

class CaseRowTest {

    @Test
    @DisplayName("a document is named by its side and its place within that side, from 1, never by its key")
    void a_document_is_named_by_its_place_within_its_side() {
        CaseRow row = Cases.bsh();
        Map<String, Integer> counts = new HashMap<>();
        assertThat(row.getDocuments()).isNotEmpty();
        for (CaseDocument document : row.getDocuments()) {
            String side = document.getDeclaredSide().toLowerCase();
            int place = counts.merge(side, 1, Integer::sum);
            assertThat(row.documentName(document.getRef())).isEqualTo(side + "#" + place);
        }
        assertThat(row.documentName("C/issuer/not-in-the-request.pdf")).isEqualTo("doc#?");
    }
}

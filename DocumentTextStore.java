package com.wp.visa131.orchestrator.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Pages of a case's documents, read from {@code document_text} (CL-137). The llm service writes that table when it
 * reads a document for the classifier call, so every page exists by the time the letter is checked; the orchestrator
 * reads only the pages a letter cites. A page with no row is absent, which the figure check reads as no support.
 */
@Component
public class DocumentTextStore {
    private final JdbcTemplate jdbcTemplate;
    private final String schema;

    public DocumentTextStore(JdbcTemplate jdbcTemplate, @Value("${visa131.schema:visa131}") String schema) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
    }

    /** Each document's text by page, positioned so page n is at index n - 1; a page not asked for is null. */
    public Map<String, List<String>> pages(Map<String, Set<Integer>> wanted) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Integer>> document : wanted.entrySet()) {
            if (document.getValue().isEmpty()) {
                continue;
            }
            List<String> pages = new ArrayList<>();
            // CL-144: a page belongs to a case_document row; the document is found by its key in document_ref
            jdbcTemplate.query(("SELECT t.page, t.text FROM %s.document_text t\n"
                    + "  JOIN %s.case_document c ON c.id = t.case_document_id\n"
                    + " WHERE c.document_ref = ? AND t.page = ANY (?)\n"
                    + " ORDER BY t.case_document_id DESC\n").formatted(schema, schema), resultSet -> {
                int page = resultSet.getInt("page");
                while (pages.size() < page) {
                    pages.add(null);
                }
                pages.set(page - 1, resultSet.getString("text"));
            }, document.getKey(), document.getValue().toArray(new Integer[0]));
            out.put(document.getKey(), pages);
        }
        return out;
    }
}

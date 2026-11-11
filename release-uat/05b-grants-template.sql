-- UAT/prod step 5, part B: THE FALLBACK, where dev's grants cannot be read (05a). The least each service needs, from
-- the code: what it reads and writes. Replace the four placeholders with this environment's users (the report
-- service shares the llm's user) and run as the owner of the tables. Identity columns need no sequence grant.

-- the orchestrator: reads the register and the pages, writes its run row
GRANT USAGE ON SCHEMA wdp_ai TO <orchestrator_user>;
GRANT SELECT ON wdp_ai.reason_code, wdp_ai.requirement, wdp_ai.requirement_time_limit, wdp_ai.requirement_record_rule,
               wdp_ai.fact_key, wdp_ai.reason_code_fact_key, wdp_ai.questionnaire_path, wdp_ai.questionnaire_field,
               wdp_ai.questionnaire_option, wdp_ai.merchant_document_type, wdp_ai.case_document, wdp_ai.document_text
      TO <orchestrator_user>;
GRANT SELECT, INSERT ON wdp_ai.classifier_run TO <orchestrator_user>;

-- the llm (and the report service, on the same user): documents, pages, token maps, facts, comparisons, usage, users
GRANT USAGE ON SCHEMA wdp_ai TO <llm_user>;
GRANT USAGE ON SCHEMA wdp TO <llm_user>;
GRANT SELECT ON ALL TABLES IN SCHEMA wdp_ai TO <llm_user>;
GRANT INSERT, UPDATE, DELETE ON wdp_ai.case_document, wdp_ai.document_text, wdp_ai.document_token_map,
                                wdp_ai.extracted_fact, wdp_ai.contest_comparison TO <llm_user>;
GRANT INSERT ON wdp_ai.model_call_usage TO <llm_user>;
GRANT INSERT, UPDATE ON wdp_ai.report_user TO <llm_user>;
GRANT SELECT ON wdp.ai_response_events TO <llm_user>;

-- the event service: its queue, the outbox, the case, the run and the comparison it updates
GRANT USAGE ON SCHEMA wdp_ai TO <event_service_user>;
GRANT USAGE ON SCHEMA wdp TO <event_service_user>;
GRANT SELECT, INSERT, UPDATE ON wdp.ai_response_events TO <event_service_user>;
GRANT SELECT ON wdp.bre_orchestration_outbox, wdp."case", wdp.action TO <event_service_user>;
GRANT SELECT ON wdp_ai.classifier_run, wdp_ai.qa_ops_questionnaire TO <event_service_user>;
GRANT SELECT, UPDATE ON wdp_ai.contest_comparison TO <event_service_user>;

-- the event service's UK datasource, on the UK database (office DB step 36)
-- GRANT USAGE ON SCHEMA nap TO <event_service_uk_user>;
-- GRANT SELECT ON nap."case", nap.action TO <event_service_uk_user>;

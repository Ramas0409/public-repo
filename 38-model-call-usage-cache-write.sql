-- Office DB step 38 (wdp_ai), POC db/V65__model_call_usage_cache_write.sql (CL-3114). Written, not run. Needs step 34.
--
-- RUN IT BEFORE DEPLOYING THE LLM THAT MAPS THE COLUMN. The llm's ModelCallUsage maps cache_write_tokens; against a
-- table without it every usage insert fails, which the recorder hides behind one WARN per call
-- ("usage not recorded: ProgrammingError"), and no usage row is written. The calls themselves are unaffected.
--
-- Run it as the role that owns wdp_ai.model_call_usage. No grant moves: the service's user keeps SELECT, INSERT.
-- The column is NULL on every row the office writes today: the office's gateway reports no cache writes. It holds the
-- prompt tokens an attempt wrote to the provider's prompt cache, priced at a model's cache_write rate in
-- MODEL_PRICES (optional; a model with none prices its writes at its input rate).
--
-- Check after: SELECT column_name, data_type, is_nullable FROM information_schema.columns
--   WHERE table_schema = 'wdp_ai' AND table_name = 'model_call_usage' AND column_name = 'cache_write_tokens';
--   -> cache_write_tokens | integer | YES

ALTER TABLE wdp_ai.model_call_usage ADD COLUMN IF NOT EXISTS cache_write_tokens integer;

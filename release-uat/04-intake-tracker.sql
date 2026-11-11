-- UAT/prod step 4 (wdp): the intake's tracking row (office DB step 30's last statement). The scheduler's intake keeps
-- its mark and its lock on it. Run once, after step 1b (or on the estate's own table). The table must otherwise stay
-- empty until a run is wanted: a running event service claims whatever rows it holds.

INSERT INTO wdp.ai_response_events (i_case, i_action_seq, c_acq_platform, c_event_type, status)
SELECT 'AI-INTAKE-00', '00', 'NAP', 'INTAKE', 'TRACKER'
WHERE NOT EXISTS (SELECT 1 FROM wdp.ai_response_events WHERE i_case = 'AI-INTAKE-00' AND status = 'TRACKER');

SELECT id, i_case, i_action_seq, status, inserted_at FROM wdp.ai_response_events ORDER BY id;

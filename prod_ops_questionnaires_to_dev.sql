-- Run in PRODUCTION (read only), for the cases to run in QA_MODE in dev. Writes the INSERT statements that put
-- operations' questionnaire for each case into DEV's wdp_ai.qa_ops_questionnaire, where a QA_MODE run looks for it.
-- Each body goes in verbatim, under action '01' (the action the run is for and the one the QA compare looks up;
-- production saves it under the response action, '02'). Only first responses are written (a body with
-- disputeResponse); a pre-arbitration body (preArbResponse) is left out, since the comparison reads the first-response
-- form. A case already in dev's table is left alone. Copy the output column into a file and run it in DEV.
WITH wanted(i_case) AS (
    VALUES ('CASE_NUMBER_1'),
           ('CASE_NUMBER_2')
)
SELECT format(
    'INSERT INTO wdp_ai.qa_ops_questionnaire (i_case, i_action_seq, c_qstnnair, x_insrt, z_insrt)'
    ' SELECT %L, ''01'', %L, ''PROD_COPY'', now()'
    ' WHERE NOT EXISTS (SELECT 1 FROM wdp_ai.qa_ops_questionnaire WHERE i_case = %L AND i_action_seq = ''01'');',
    q.i_case, q.c_qstnnair, q.i_case)
FROM wanted w
JOIN wdp.disputes_questionnaire q ON q.i_case = w.i_case
WHERE q.c_qstnnair IS NOT NULL
  AND q.c_qstnnair::jsonb ? 'disputeResponse'
ORDER BY q.i_case;

-- 10 piece 2 of 3: the CHECK (same session, transaction still open)
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'wdp_ai.requirement_time_limit'::regclass AND contype = 'c'
                AND pg_get_constraintdef(oid) LIKE '%conditions%'
    LOOP
        EXECUTE format('ALTER TABLE wdp_ai.requirement_time_limit DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE wdp_ai.requirement_time_limit ADD CONSTRAINT requirement_time_limit_conditions_check
    CHECK (conditions <@ ARRAY[
        'expectedDate', 'noExpectedDate', 'bankruptNotYes', 'cancellationKnown', 'mccIsAgency', 'mccNotAgency',
        'coveredByScheme', 'coveredNotYes', 'schemeInsufficientNotYes', 'notBondedInsolvency'
    ]::text[]);
-- expected: DO, ALTER TABLE. Then piece 3 in the same session.

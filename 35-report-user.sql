-- Office DB step 35 (wdp_ai), POC db/V62__report_user.sql (CL-3065). Written, not run: the report service's users,
-- the one table the report owns. The report writes it through its auth connection alone (login reads it; the password
-- page and the admin pages write it); every report query stays on the read-only connection. Rows are never deleted.
-- Needs no earlier step but the schema. Safe to run again.
--
-- Run it as the role the report service connects as (its wdp_datasource_username), or as the role that owns
-- wdp_ai.extracted_fact and then: GRANT SELECT, INSERT, UPDATE ON wdp_ai.report_user TO <that username>; without the
-- grant the login fails with a ProgrammingError and the page cannot be reached. The report starts with no user until
-- REPORT_BOOTSTRAP_ADMIN_EMAIL and REPORT_BOOTSTRAP_ADMIN_PASSWORD_HASH are set: the first start then inserts that
-- admin, who must change the password at the first login.

BEGIN;

CREATE TABLE IF NOT EXISTS wdp_ai.report_user (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email                 varchar(254) NOT NULL,
    password_hash         varchar(72)  NOT NULL,
    role                  varchar(8)   NOT NULL,
    active                boolean      NOT NULL DEFAULT true,
    must_change_password  boolean      NOT NULL DEFAULT true,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    created_by            varchar(254),
    password_changed_at   timestamptz,
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT report_user_email_unique UNIQUE (email),
    CONSTRAINT report_user_email_lower_check CHECK (email = lower(email)),
    CONSTRAINT report_user_role_check CHECK (role IN ('ADMIN', 'REGULAR'))
);

COMMIT;

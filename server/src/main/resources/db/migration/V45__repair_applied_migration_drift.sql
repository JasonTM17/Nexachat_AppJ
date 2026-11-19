-- Repairs schema drift left by applied migrations that were edited in place
-- after some databases had already run them. Three drift classes exist; a
-- forward migration can repair the first two:
--
--   V36 edit-in-place (28d8a492, c99ef64c): widened admin_audit_log.target_id
--     to TEXT and alert_rules.name/description to 200/500. Databases that
--     applied the original still hold VARCHAR(64)/VARCHAR(80)/VARCHAR(400);
--     the 64-char target_id overflows on comma-joined bulk ids and 500s the
--     endpoint. Repaired below.
--   V28 edit-in-place (0aea55645, ac29e4f1e): after V28 had merged and been
--     applied, google_signin_nonces gained the user_id owner column with its
--     partial index, then the access_token_id_hash binding column with the
--     ck_google_nonce_binding CHECK. Databases that applied the first
--     version of V28 lack all four and the link-nonce endpoints 500 at
--     runtime. Repaired below.
--   The V27__google_signin.sql -> V28 rename (a974b1884) is NOT repairable by
--     a forward migration: a database that ran the old V27 has a history row
--     for version 27 and none for 28, so the current V28 would re-run and
--     collide on CREATE TABLE. Such a database must be reconciled once by
--     hand; see "Repairing applied-migration drift" in
--     docs/operations/deploy-render.md. The repo-hygiene gate
--     (tools/checks/check-migrations-immutable.py) now rejects renamed
--     applied migrations, so this class must not recur.
--
-- OPERATOR NOTE: a database that applied V10, V28 or V36 before those files'
-- post-merge edits holds stale checksums in flyway_schema_history, and prod
-- validates on migrate (spring.flyway.validate-on-migrate=true,
-- application-prod.yml:47). Validation therefore aborts BEFORE this
-- migration can run. Run `flyway repair` once before deploying this build:
-- it realigns the recorded checksums with the edited files (on a database
-- that applied the pre-rename V27__google_signin.sql it also realigns the
-- version-27 row to the current V27__account_recovery_email_otp.sql, so
-- that migration's objects must then be applied by hand — see the special
-- case in the runbook). See docs/operations/deploy-render.md,
-- "Repairing applied-migration drift".
--
-- Fresh databases already have these shapes via the edited sources, so every
-- statement below is idempotent.

-- V36 edit-in-place repairs.
ALTER TABLE admin_audit_log ALTER COLUMN target_id TYPE TEXT;
ALTER TABLE alert_rules ALTER COLUMN name TYPE VARCHAR(200);
ALTER TABLE alert_rules ALTER COLUMN description TYPE VARCHAR(500);

-- Belt-and-suspenders, not a repair: the original V10 (90799e16c) already
-- contained the unconditional ADD CONSTRAINT uq_user_device_token; the later
-- edit (c2d0a4142) only wrapped it in existence guards, so databases that
-- applied either version hold the constraint. Kept as a guard for any
-- database that somehow predates it.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'user_device_tokens'::regclass
          AND conname = 'uq_user_device_token'
    ) THEN
        ALTER TABLE user_device_tokens
            ADD CONSTRAINT uq_user_device_token UNIQUE (user_id, token);
    END IF;
END $$;

-- V28 edit-in-place repairs: bind link nonces to the requesting account and
-- access-token instance, exactly as the edited V28 defines them.
ALTER TABLE google_signin_nonces
    ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE google_signin_nonces
    ADD COLUMN IF NOT EXISTS access_token_id_hash CHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'google_signin_nonces'::regclass
          AND conname = 'ck_google_nonce_binding'
    ) THEN
        ALTER TABLE google_signin_nonces
            ADD CONSTRAINT ck_google_nonce_binding CHECK (
                (purpose = 'signin' AND user_id IS NULL AND access_token_id_hash IS NULL)
                OR (purpose = 'link' AND user_id IS NOT NULL AND access_token_id_hash IS NOT NULL
                    AND length(btrim(access_token_id_hash)) = 64)
            );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_google_signin_nonces_owner ON google_signin_nonces (user_id)
    WHERE user_id IS NOT NULL;

-- Defensive guard, not a repair: user_identities has existed since V28's
-- first version (it shipped as V27__google_signin.sql before the V27->V28
-- rename), so any database that applied V28 already holds this table. This
-- covers databases created before the rename window that were reconciled by
-- dropping the V27-created objects (see the runbook) and any database whose
-- history row for V28 was inserted manually without running V28 itself.
CREATE TABLE IF NOT EXISTS user_identities (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider      VARCHAR(32)  NOT NULL,
    subject       VARCHAR(255) NOT NULL,
    email_at_link VARCHAR(320),
    linked_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_identities_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_user_identities_user_provider UNIQUE (user_id, provider),
    CONSTRAINT ck_user_identities_provider CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_user_identities_subject CHECK (btrim(subject) <> '')
);

CREATE INDEX IF NOT EXISTS idx_user_identities_user ON user_identities (user_id);

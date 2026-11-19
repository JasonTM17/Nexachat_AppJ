-- Repairs schema drift left by migration files that were edited in place
-- after some databases had already applied them:
--
--   V36 (28d8a492, c99ef64c) widened admin_audit_log.target_id to TEXT and
--     alert_rules.name/description to 200/500. Databases that applied the
--     original still hold VARCHAR(64)/VARCHAR(80)/VARCHAR(400); the 64-char
--     target_id overflows on comma-joined bulk ids and 500s the endpoint.
--   V10 appended the uq_user_device_token unique constraint.
--   V28 appended the user_identities table.
--
-- Fresh databases already have these shapes via the edited sources, so every
-- statement below is idempotent.

ALTER TABLE admin_audit_log ALTER COLUMN target_id TYPE TEXT;
ALTER TABLE alert_rules ALTER COLUMN name TYPE VARCHAR(200);
ALTER TABLE alert_rules ALTER COLUMN description TYPE VARCHAR(500);

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

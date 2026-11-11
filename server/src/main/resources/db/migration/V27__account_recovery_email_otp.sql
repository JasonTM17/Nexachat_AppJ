ALTER TABLE users
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT users_email_normalized_check
        CHECK (email IS NULL OR email = LOWER(BTRIM(email))),
    ADD CONSTRAINT users_email_verification_requires_email_check
        CHECK (email IS NOT NULL OR email_verified_at IS NULL);

CREATE UNIQUE INDEX users_verified_email_active_key
    ON users (email)
    WHERE email_verified_at IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE email_otps (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose TEXT NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'DELETE_ACCOUNT', 'RESET_PASSWORD')),
    email VARCHAR(254) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT email_otps_email_normalized_check CHECK (email = LOWER(BTRIM(email)))
);

CREATE UNIQUE INDEX email_otps_one_active_per_purpose
    ON email_otps (user_id, purpose)
    WHERE consumed_at IS NULL;

CREATE INDEX email_otps_user_purpose_created_idx
    ON email_otps (user_id, purpose, created_at DESC);

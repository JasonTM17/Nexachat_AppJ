-- V41: one-time codes that prove ownership of an email address before an
-- account exists, so a person can register with an email instead of a phone.

CREATE TABLE signup_email_otps (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT signup_email_otps_email_normalized_check CHECK (email = LOWER(BTRIM(email)))
);

CREATE UNIQUE INDEX signup_email_otps_one_active_per_email
    ON signup_email_otps (email)
    WHERE consumed_at IS NULL;

CREATE INDEX signup_email_otps_email_created_idx
    ON signup_email_otps (email, created_at DESC);

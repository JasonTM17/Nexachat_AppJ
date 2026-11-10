-- V27: "Sign in with Google" identity links and single-use nonces.
--
-- The Google account identity key is the OpenID Connect `sub` claim, never the
-- email address: a Google account can change its email over time. One user may
-- link at most one Google account and one Google account may belong to at most
-- one NexaChat user (both enforced by UNIQUE constraints).
--
-- Deleting a user removes the links (the account-deletion branch tombstones
-- users instead of deleting rows, so it must delete identity rows explicitly).
--
-- Nonces are stored as SHA-256 hex only; the raw value is returned to the
-- client once and is consumed atomically before a session is issued.

CREATE TABLE user_identities (
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

CREATE INDEX idx_user_identities_user ON user_identities (user_id);

CREATE TABLE google_signin_nonces (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nonce_hash  CHAR(64)    NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_google_nonce_hash CHECK (length(btrim(nonce_hash)) = 64),
    CONSTRAINT ck_google_nonce_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_google_signin_nonces_expiry ON google_signin_nonces (expires_at);

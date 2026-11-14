-- E2EE direct chats (v1): per-device X25519 public keys, an opt-in flag on
-- DIRECT conversations, and the opaque ciphertext metadata on messages.
CREATE TABLE e2ee_devices (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    public_key  BYTEA NOT NULL CHECK (octet_length(public_key) = 32),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ NULL
);
CREATE INDEX idx_e2ee_devices_active_user ON e2ee_devices(user_id) WHERE revoked_at IS NULL;

ALTER TABLE conversations ADD COLUMN e2ee_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE conversations ADD CONSTRAINT chk_conversations_e2ee_direct
    CHECK (NOT e2ee_enabled OR type = 'DIRECT');

ALTER TABLE messages ADD COLUMN encryption VARCHAR(10) NULL;
ALTER TABLE messages ADD COLUMN e2ee_envelopes JSONB NULL;
ALTER TABLE messages ADD CONSTRAINT chk_messages_encryption
    CHECK (encryption IS NULL OR encryption = 'E2EE_V1');
ALTER TABLE messages ADD CONSTRAINT chk_messages_e2ee_pair
    CHECK ((encryption IS NULL) = (e2ee_envelopes IS NULL));

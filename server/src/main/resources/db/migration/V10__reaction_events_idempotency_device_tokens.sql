-- V10: reaction change events, idempotent request replay, per-user device tokens.
--
-- reaction_events is an append-only audit of every reaction add/remove. The
-- delta sync derives reaction updates from it, so a removal (which DELETEs the
-- message_reactions row) is still visible to an offline client. The table is
-- never updated; rows are retained for a bounded window and purged by a
-- scheduled job.
--
-- request_idempotency caches the outcome of a mutating request keyed by
-- (user_id, key). A replayed key returns the original result without running
-- the operation again; the same key on a different endpoint or payload is a
-- client bug and is rejected. Rows expire after a bounded TTL.
--
-- user_device_tokens moves from UNIQUE(token) to UNIQUE(user_id, token): a
-- shared physical device can hold one row per account without evicting the
-- previous account's push registration.

CREATE TABLE reaction_events (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id      UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji           VARCHAR(16) NOT NULL,
    action          VARCHAR(6)  NOT NULL CHECK (action IN ('ADD', 'REMOVE')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The delta query filters by (conversation_id, created_at) and pages by id.
CREATE INDEX idx_reaction_events_conv_created ON reaction_events (conversation_id, created_at);
CREATE INDEX idx_reaction_events_conv_id ON reaction_events (conversation_id, id);

CREATE TABLE request_idempotency (
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key           VARCHAR(64)  NOT NULL,
    endpoint      VARCHAR(255) NOT NULL,
    payload_hash  VARCHAR(64)  NOT NULL,
    response_hash VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

CREATE INDEX idx_request_idempotency_created ON request_idempotency (created_at);

-- Data-safe: UNIQUE(token) already guarantees (user_id, token) is unique, so
-- existing rows satisfy the new constraint. The old single-column index is
-- redundant with the leftmost column of the new unique index.
-- Guarded so databases created from an earlier V7 draft that already carry the
-- per-user constraint still migrate.
ALTER TABLE user_device_tokens DROP CONSTRAINT IF EXISTS uq_device_token;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'user_device_tokens'::regclass AND conname = 'uq_user_device_token'
    ) THEN
        ALTER TABLE user_device_tokens ADD CONSTRAINT uq_user_device_token UNIQUE (user_id, token);
    END IF;
END $$;
DROP INDEX IF EXISTS idx_device_tokens_user;

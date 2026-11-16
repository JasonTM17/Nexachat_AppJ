-- Disappearing messages: a per-conversation timer and a per-message expiry
-- instant stamped at send time. disappearing_seconds NULL (or 0) means the
-- timer is off; only the allow-listed durations are stored.
ALTER TABLE conversations ADD COLUMN disappearing_seconds INT NULL;
ALTER TABLE conversations ADD CONSTRAINT chk_conversations_disappearing_seconds
    CHECK (disappearing_seconds IS NULL
           OR disappearing_seconds IN (0, 3600, 86400, 604800, 2592000, 7776000));

ALTER TABLE messages ADD COLUMN expires_at TIMESTAMPTZ NULL;

-- The purge scan only ever walks live rows with an expiry set.
CREATE INDEX idx_messages_expires_at ON messages (expires_at)
    WHERE expires_at IS NOT NULL AND deleted_at IS NULL;

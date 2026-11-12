-- V32: bounded, commit-safe per-conversation message change cursors.
-- Run only while message writers and sync-v2 requests are quiesced. Flyway
-- executes this file transactionally; the backfill is one atomic baseline.

ALTER TABLE conversations
    ADD COLUMN change_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN sync_generation BIGINT NOT NULL DEFAULT 1;

ALTER TABLE messages
    ADD COLUMN change_version BIGINT NOT NULL DEFAULT 0;

WITH ranked_messages AS (
    SELECT id,
           row_number() OVER (PARTITION BY conversation_id ORDER BY seq, id) AS revision
    FROM messages
)
UPDATE messages m
SET change_version = ranked_messages.revision
FROM ranked_messages
WHERE ranked_messages.id = m.id;

UPDATE conversations c
SET change_version = COALESCE((
        SELECT MAX(m.change_version)
        FROM messages m
        WHERE m.conversation_id = c.id
    ), 0),
    sync_generation = 1;

CREATE INDEX idx_messages_conv_change_version
    ON messages (conversation_id, change_version, id);

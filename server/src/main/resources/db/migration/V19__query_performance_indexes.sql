-- V19: delta-sync indexes for edited and soft-deleted message scans.
--
-- The delta sync asks, per conversation, "which messages were edited after
-- <since>" and "which were deleted after <since>". Both predicates are scoped
-- by conversation_id, but no index carried edited_at or deleted_at, so every
-- request fell back to a full scan of the conversation's messages (8k rows in
-- the audit fixture, unbounded in production) and discarded almost all of
-- them. Because the vast majority of messages are never edited and never
-- deleted, the indexes are partial: they stay small, and the common insert
-- path does not touch them at all.

CREATE INDEX IF NOT EXISTS idx_messages_conv_edited
    ON messages (conversation_id, edited_at)
    WHERE edited_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_messages_conv_deleted
    ON messages (conversation_id, deleted_at)
    WHERE deleted_at IS NOT NULL;

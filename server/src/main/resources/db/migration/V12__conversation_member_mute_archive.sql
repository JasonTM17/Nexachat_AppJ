-- =============================================================================
-- V12__conversation_member_mute_archive.sql
-- Per-member conversation mute and archive preferences
-- =============================================================================

-- Each member carries its own preference columns on conversation_members:
--   * muted / muted_until — a mute is either forever (muted_until NULL) or until
--     an instant. A past instant is treated as expired by every read path, so
--     no cleanup job is required.
--   * archived — hidden from the default inbox, restorable on request.
--
-- Both flags default to FALSE, so existing rows and clients that do not send
-- the new inbox filter see no change until a member opts in. The columns are
-- per-member by construction: they live on the (conversation_id, user_id) row
-- and are never readable through another member's projection.

ALTER TABLE conversation_members
    ADD COLUMN muted       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN muted_until TIMESTAMPTZ NULL,
    ADD COLUMN archived    BOOLEAN     NOT NULL DEFAULT FALSE;

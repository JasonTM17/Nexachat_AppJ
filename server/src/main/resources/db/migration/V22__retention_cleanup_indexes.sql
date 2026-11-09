-- Retention jobs select small, ordered batches by expiry/terminal timestamps.
-- These indexes keep candidate discovery bounded as the underlying tables grow.

CREATE INDEX idx_refresh_tokens_expiry_retention
    ON refresh_tokens (expires_at, id);

CREATE INDEX idx_refresh_tokens_revoked_retention
    ON refresh_tokens (revoked_at, id)
    WHERE revoked_at IS NOT NULL;

CREATE INDEX idx_device_tokens_last_used_retention
    ON user_device_tokens (last_used_at, id)
    WHERE last_used_at IS NOT NULL;

CREATE INDEX idx_device_tokens_never_used_retention
    ON user_device_tokens (updated_at, id)
    WHERE last_used_at IS NULL;

CREATE INDEX idx_join_requests_reviewed_retention
    ON group_join_requests (reviewed_at, id)
    WHERE status IN ('APPROVED', 'REJECTED');

CREATE INDEX idx_join_requests_link_retention
    ON group_join_requests (invite_link_id)
    WHERE invite_link_id IS NOT NULL;

CREATE INDEX idx_invite_links_expiry_retention
    ON group_invite_links (expires_at, id)
    WHERE expires_at IS NOT NULL;

CREATE INDEX idx_invite_links_revoked_retention
    ON group_invite_links (revoked_at, id)
    WHERE revoked_at IS NOT NULL;

CREATE INDEX idx_invite_links_used_retention
    ON group_invite_links (updated_at, id)
    WHERE max_uses IS NOT NULL AND use_count >= max_uses;

CREATE INDEX idx_invite_bans_link_retention
    ON group_invite_bans (invite_link_id, id)
    WHERE invite_link_id IS NOT NULL;

CREATE INDEX idx_invite_bans_created_retention
    ON group_invite_bans (created_at, id)
    WHERE invite_link_id IS NOT NULL;

CREATE INDEX idx_call_records_ended_retention
    ON call_records (ended_at, call_id)
    WHERE state = 'ENDED';

CREATE INDEX idx_attachments_unbound_retention
    ON message_attachments (created_at, id)
    WHERE message_id IS NULL;

CREATE INDEX idx_messages_deleted_retention
    ON messages (deleted_at, conversation_id, id)
    WHERE deleted_at IS NOT NULL;

CREATE INDEX idx_reaction_events_created_retention
    ON reaction_events (created_at, id, conversation_id);

CREATE INDEX idx_polls_ended_retention
    ON polls (
        LEAST(
            COALESCE(closed_at, 'infinity'::timestamptz),
            COALESCE(closes_at, 'infinity'::timestamptz),
            COALESCE(deleted_at, 'infinity'::timestamptz)
        ),
        id
    )
    WHERE closed_at IS NOT NULL OR closes_at IS NOT NULL OR deleted_at IS NOT NULL;

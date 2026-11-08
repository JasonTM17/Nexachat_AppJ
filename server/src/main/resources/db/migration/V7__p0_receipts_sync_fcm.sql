-- =============================================================================
-- V7__p0_receipts_sync_fcm.sql
-- Milestone M1: P0 Receipts, Delta Sync Covering Index, and Device Tokens
-- =============================================================================

-- 1. Add delivery watermark column to conversation_members
ALTER TABLE conversation_members
    ADD COLUMN last_delivered_seq BIGINT NOT NULL DEFAULT 0;

-- 2. Covering index for Delta Sync catch-up on messages table
CREATE INDEX idx_messages_delta_sync ON messages (conversation_id, seq)
    INCLUDE (edited_at, deleted_at);

-- 3. Device Tokens table for FCM Push Notifications
CREATE TABLE user_device_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token        VARCHAR(512) NOT NULL,
    platform     VARCHAR(10)  NOT NULL DEFAULT 'ANDROID' CHECK (platform IN ('ANDROID', 'IOS')),
    device_label VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    CONSTRAINT uq_device_token UNIQUE (token)
);

CREATE INDEX idx_device_tokens_user ON user_device_tokens (user_id);

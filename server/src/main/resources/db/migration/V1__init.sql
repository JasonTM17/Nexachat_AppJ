-- V1: core schema for NexaChat.
-- Extensions must exist before the objects that depend on them.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone         VARCHAR(16)  NOT NULL CHECK (phone ~ '^\+[1-9]\d{7,14}$'),
    password_hash CHAR(60)     NOT NULL,
    display_name  VARCHAR(60)  NOT NULL,
    avatar_url    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_phone_key UNIQUE (phone)
);

CREATE INDEX idx_users_display_name_trgm ON users USING gin (display_name gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- refresh_tokens
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   CHAR(64)    NOT NULL UNIQUE,
    device_label VARCHAR(80),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID
);

CREATE INDEX idx_refresh_user ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- contacts
-- ---------------------------------------------------------------------------
CREATE TABLE contacts (
    owner_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    alias_name     VARCHAR(60),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, contact_user_id),
    CONSTRAINT contacts_owner_not_self CHECK (owner_id <> contact_user_id)
);

CREATE INDEX idx_contacts_user ON contacts (contact_user_id);

-- ---------------------------------------------------------------------------
-- conversations
-- ---------------------------------------------------------------------------
CREATE TABLE conversations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type            VARCHAR(6)  NOT NULL CHECK (type IN ('DIRECT', 'GROUP')),
    direct_key      VARCHAR(80) UNIQUE,
    title           VARCHAR(80),
    avatar_url      VARCHAR(255),
    created_by      UUID        NOT NULL REFERENCES users(id),
    last_message_id UUID,
    last_message_seq BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT conversations_direct_key_required
        CHECK ((type = 'DIRECT') = (direct_key IS NOT NULL))
);

CREATE INDEX idx_conv_inbox ON conversations (last_message_seq DESC NULLS LAST);

-- ---------------------------------------------------------------------------
-- conversation_members
-- ---------------------------------------------------------------------------
CREATE TABLE conversation_members (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            VARCHAR(6)  NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'MEMBER')),
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_seq   BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_members_user ON conversation_members (user_id);

-- ---------------------------------------------------------------------------
-- messages
-- ---------------------------------------------------------------------------
CREATE TABLE messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seq               BIGSERIAL   NOT NULL UNIQUE,
    conversation_id   UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id         UUID        NOT NULL REFERENCES users(id),
    client_message_id UUID        NOT NULL,
    type              VARCHAR(7)  NOT NULL CHECK (type IN ('TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'FILE', 'SYSTEM')),
    content           TEXT,
    reply_to_id       UUID        REFERENCES messages(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at         TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT messages_sender_client_unique UNIQUE (sender_id, client_message_id)
);

CREATE INDEX idx_msg_conv_seq ON messages (conversation_id, seq DESC);

-- ---------------------------------------------------------------------------
-- message_attachments
-- ---------------------------------------------------------------------------
CREATE TABLE message_attachments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id    UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    storage_key   VARCHAR(120) NOT NULL UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(80)  NOT NULL,
    size_bytes    BIGINT      NOT NULL,
    width         INT,
    height        INT,
    duration_ms   INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_att_msg ON message_attachments (message_id);

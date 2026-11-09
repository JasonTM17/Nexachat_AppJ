-- V20: group invite links with admin approval (Brief 206).
--
-- A GROUP admin creates/rotates/revokes an invite link token (high-entropy,
-- stored hashed via SHA-256, optional expiry up to 30 days, optional max uses),
-- and toggles "member approval required".
--
-- Anyone authenticated can resolve a link to a safe preview (title, avatar,
-- member count only; never member lists), then join directly (if approval is
-- not required) or submit a join request (if approval is required).
--
-- Banned/removed-by-admin users cannot rejoin via the same link unless an
-- admin re-invites.

CREATE TABLE group_invite_links (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    created_by          UUID        NOT NULL REFERENCES users(id),
    token_hash          VARCHAR(64) NOT NULL UNIQUE,
    requires_approval   BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at          TIMESTAMPTZ,
    max_uses            INT         CHECK (max_uses IS NULL OR max_uses > 0),
    use_count           INT         NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    revoked_at          TIMESTAMPTZ,
    revoked_by          UUID        REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT group_invite_links_revoked_by_consistent
        CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

CREATE UNIQUE INDEX idx_group_invite_links_one_active
    ON group_invite_links (conversation_id)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_group_invite_links_token_hash
    ON group_invite_links (token_hash);

CREATE TABLE group_join_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    invite_link_id      UUID        REFERENCES group_invite_links(id) ON DELETE SET NULL,
    user_id             UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at         TIMESTAMPTZ,
    reviewed_by         UUID        REFERENCES users(id),
    CONSTRAINT group_join_requests_reviewed_consistent
        CHECK ((status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL)
            OR (status <> 'PENDING' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL))
);

CREATE UNIQUE INDEX idx_group_join_requests_user_conv_pending
    ON group_join_requests (conversation_id, user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_group_join_requests_conv_status
    ON group_join_requests (conversation_id, status, created_at DESC);

CREATE INDEX idx_group_join_requests_user
    ON group_join_requests (user_id, conversation_id, created_at DESC);

CREATE TABLE group_invite_bans (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id             UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invite_link_id      UUID        REFERENCES group_invite_links(id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_group_invite_bans_lookup
    ON group_invite_bans (conversation_id, user_id, invite_link_id);

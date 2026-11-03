-- V3: message interactions (phase-10 creative feature) — emoji reactions and
-- pinned messages. Reply-to already shipped in V1 (messages.reply_to_id), so
-- this file only adds the two new tables from plan §9.2.
--
-- message_reactions: one row per (message, user, emoji) — the composite PK IS
-- the toggle idempotency contract: a repeated reaction is a DELETE of the row,
-- a duplicate insert is a no-op (ON CONFLICT DO NOTHING). Reactions die with
-- their message and their user (ON DELETE CASCADE — a deleted message keeps no
-- dangling reaction rows, matching the soft-delete-FK model of plan §9.3.6).
--
-- pinned_messages: one row per pinned (conversation, message) pair — the PK
-- makes "pin the same message twice" a no-op by construction. The ≤3-per-
-- conversation ceiling is enforced in PinService under a row lock on the
-- conversation (no DB counter needed: pin/unpin serialize on the parent row).
CREATE TABLE message_reactions (
    message_id UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji      VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id, emoji)
);

-- Reverse lookup: a user's reactions (and the user-side cascade delete).
CREATE INDEX idx_reactions_user ON message_reactions (user_id);

CREATE TABLE pinned_messages (
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id      UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    pinned_by       UUID        NOT NULL REFERENCES users(id),
    pinned_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, message_id)
);

-- Leftmost PK column is conversation_id, so the message-side cascade delete
-- and "is this message pinned anywhere" lookups need their own index.
CREATE INDEX idx_pins_message ON pinned_messages (message_id);

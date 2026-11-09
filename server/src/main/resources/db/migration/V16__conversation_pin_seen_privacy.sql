-- V16: per-member conversation pin and the "seen" privacy switch.
--
-- conversation_members carries the caller's own pin state. A pinned
-- conversation sorts ahead of the active inbox order; pinned_at records when
-- the current pin started and is NULL while the row is not pinned. The
-- check constraint keeps the pair consistent, so a half-written pin can never
-- be observed by the inbox, delta sync or the pin-limit count.
--
-- users.show_seen is the account-level read-receipt switch (default TRUE, so
-- existing accounts keep today's behaviour). When FALSE, the user's
-- MESSAGE_READ receipts are not broadcast and the user does not receive other
-- members' receipts.

ALTER TABLE conversation_members
    ADD COLUMN pinned    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN pinned_at TIMESTAMPTZ NULL;

ALTER TABLE conversation_members
    ADD CONSTRAINT conversation_members_pinned_at_check
        CHECK ((pinned = FALSE AND pinned_at IS NULL)
            OR (pinned = TRUE AND pinned_at IS NOT NULL));

-- The pin-limit count filters by user and pinned state; the partial index
-- keeps that count bounded to the caller's own pinned rows.
CREATE INDEX idx_conversation_members_user_pinned
    ON conversation_members (user_id, pinned_at DESC)
    WHERE pinned = TRUE;

ALTER TABLE users
    ADD COLUMN show_seen BOOLEAN NOT NULL DEFAULT TRUE;

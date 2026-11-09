-- V14: conversation reminders ("nhac hen").
--
-- A reminder is visible to every member of its conversation and is fired by a
-- durable database-backed scheduler: due rows are claimed with
-- FOR UPDATE SKIP LOCKED, pushed through FCM, then advanced or completed.
--
-- anchor_at is the immutable first occurrence of a repeating reminder. The
-- next occurrence is always derived from the anchor, so adding months clamps
-- to the month end (31 Jan -> 28/29 Feb -> 31 Mar) without drifting.
--
-- fired_at marks a completed one-off reminder; those rows are retained briefly
-- for idempotent replays and then purged. Repeating reminders keep
-- fired_at NULL and move remind_at forward. The 50-active-reminder ceiling
-- per conversation counts rows with fired_at IS NULL.

CREATE TABLE conversation_reminders (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID         NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    created_by      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(200) NOT NULL,
    remind_at       TIMESTAMPTZ  NOT NULL,
    anchor_at       TIMESTAMPTZ  NOT NULL,
    repeat_rule     VARCHAR(7)   NOT NULL DEFAULT 'NONE'
                                 CHECK (repeat_rule IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY')),
    fired_at        TIMESTAMPTZ,
    last_fired_at   TIMESTAMPTZ,
    claimed_at      TIMESTAMPTZ,
    claim_token     UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT reminders_title_length CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT reminders_claim_pair CHECK ((claimed_at IS NULL) = (claim_token IS NULL))
);

-- The scheduler's due scan: oldest due, unclaimed/expired-claim rows first.
CREATE INDEX idx_reminders_due ON conversation_reminders (remind_at, id)
    WHERE fired_at IS NULL;

-- The 50-active-per-conversation count and the upcoming list.
CREATE INDEX idx_reminders_conv_active ON conversation_reminders (conversation_id, remind_at)
    WHERE fired_at IS NULL;

-- Retention sweep for completed one-off reminders.
CREATE INDEX idx_reminders_fired ON conversation_reminders (fired_at)
    WHERE fired_at IS NOT NULL;

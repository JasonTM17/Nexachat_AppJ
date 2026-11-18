-- V42: chat streaks.
--
-- conversation_streaks holds one row per DIRECT conversation once either side
-- has sent a message. user_low_id/user_high_id are the two members ordered by
-- their canonical lowercase uuid text (the same ordering as
-- conversations.direct_key), so a pair maps to one row whichever side writes
-- first. low_day/high_day are the last streak day each side sent on; last_day
-- is the last day BOTH sides had sent, which is what count is measured
-- against. freeze_used_on records the one automatic grace day a chain may
-- consume per rolling week.
--
-- user_streaks is the app-wide check-in streak: open_day is the last day the
-- client reported opening the app, sent_day the last day the user sent
-- something. The same engine advances it when both fall on one day.
-- reminder_enabled / reminder_sent_day back the opt-in daily reminder push.

CREATE TABLE conversation_streaks (
    conversation_id UUID        PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
    user_low_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_high_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    low_day         DATE,
    high_day        DATE,
    count           INTEGER     NOT NULL DEFAULT 0 CHECK (count >= 0),
    best_count      INTEGER     NOT NULL DEFAULT 0 CHECK (best_count >= 0),
    last_day        DATE,
    started_on      DATE,
    freeze_used_on  DATE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT conversation_streaks_pair_order CHECK (user_low_id::text < user_high_id::text)
);

CREATE INDEX idx_conversation_streaks_low ON conversation_streaks (user_low_id);
CREATE INDEX idx_conversation_streaks_high ON conversation_streaks (user_high_id);

CREATE TABLE user_streaks (
    user_id           UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    open_day          DATE,
    sent_day          DATE,
    count             INTEGER     NOT NULL DEFAULT 0 CHECK (count >= 0),
    best_count        INTEGER     NOT NULL DEFAULT 0 CHECK (best_count >= 0),
    last_day          DATE,
    started_on        DATE,
    freeze_used_on    DATE,
    reminder_enabled  BOOLEAN     NOT NULL DEFAULT FALSE,
    reminder_sent_day DATE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The reminder job only scans opted-in users.
CREATE INDEX idx_user_streaks_reminder ON user_streaks (user_id) WHERE reminder_enabled;

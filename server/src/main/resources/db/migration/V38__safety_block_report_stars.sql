-- V38: user blocking, abuse reports, and per-user starred messages.
--
-- user_blocks is a DIRECTED pair: (blocker, blocked) means "blocker does not
-- want contact from blocked". The PK makes a repeat block a no-op; the CHECK
-- rejects self-blocks at the storage layer. Both directions are indexed: the
-- blocker side serves "list people I blocked" and the blocked side serves
-- "did this person block me" on every send/call/presence path.
--
-- messages.peer_suppressed is the durable form of "accepted but never
-- delivered": when a user sends to a direct peer who blocked them, the row is
-- stored with the flag set so history, sync, unread counts, inbox preview and
-- starred lists all hide it from that peer — and unblocking later does NOT
-- retro-deliver it. The sender still sees their own row (sends stay
-- indistinguishable to the blocked user: no block leak).
--
-- reports holds one abuse report per row. target_id is intentionally
-- polymorphic (user | message | conversation id per target_type) so the
-- admin console can join either side later; no FK keeps the schema stable for
-- that console. The partial unique index makes a second OPEN report on the
-- same target from the same reporter impossible at the storage layer — the
-- service returns the existing row instead (idempotent reporting).
-- message_snapshot is the evidence text: server-copied plaintext truncated to
-- 500 chars, or reporter-supplied ciphertext plaintext for E2EE chats — in
-- which case snapshot_reporter_provided marks that the server never read it.
--
-- message_stars is per-user (unlike pinned_messages which is per
-- conversation): one user stars a message for themselves. Both FKs cascade
-- so message recall-by-deletion and account removal drop stars; reads still
-- re-check membership so leaving a group hides its starred rows.

CREATE TABLE user_blocks (
    blocker_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_id);

ALTER TABLE messages
    ADD COLUMN peer_suppressed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE reports (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id                 UUID         NOT NULL REFERENCES users(id),
    target_type                 VARCHAR(12)  NOT NULL
        CHECK (target_type IN ('USER', 'MESSAGE', 'CONVERSATION')),
    target_id                   UUID         NOT NULL,
    reason                      VARCHAR(12)  NOT NULL
        CHECK (reason IN ('SPAM', 'HARASSMENT', 'VIOLENCE', 'SEXUAL', 'SCAM', 'OTHER')),
    details                     VARCHAR(500),
    message_snapshot            VARCHAR(500),
    snapshot_reporter_provided  BOOLEAN      NOT NULL DEFAULT FALSE,
    status                      VARCHAR(9)   NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'REVIEWED', 'DISMISSED')),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_reports_open_dedupe
    ON reports (reporter_id, target_type, target_id)
    WHERE status = 'OPEN';

CREATE INDEX idx_reports_status_created ON reports (status, created_at);
CREATE INDEX idx_reports_reporter_created ON reports (reporter_id, created_at DESC);

CREATE TABLE message_stars (
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_id UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, message_id)
);

-- The starred list is paged newest-first per user; the pair index covers the
-- keyset predicate (created_at, message_id) without a sort step.
CREATE INDEX idx_message_stars_user_created
    ON message_stars (user_id, created_at DESC, message_id);
CREATE INDEX idx_message_stars_message ON message_stars (message_id);

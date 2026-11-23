-- V46: scheduled messages.
--
-- A scheduled message is a TEXT message the author asks the server to deliver
-- later into one of their conversations. Due rows are claimed with
-- FOR UPDATE SKIP LOCKED under a lease token, sent through the ordinary
-- MessageService.send path (client_message_id doubles as the send
-- idempotency key, so a crash between send and mark is deduplicated by
-- messages_sender_client_unique), then moved to a terminal status. A send is
-- never early: the claim scan only picks rows whose send_at has passed, so a
-- sleeping instance simply delivers late on its next wake.
--
-- status is PENDING -> SENT | CANCELED | FAILED. Every terminal transition
-- scrubs body to '' for privacy and retention hygiene; the idempotency key
-- and audit fields stay so POST replays keep answering with the outcome.
-- CANCELED may still carry a failure_code when the cancellation itself needs
-- a reason (account deletion writes ACCOUNT_DELETED).
--
-- claimed_at/claim_token is the dispatcher's lease pair — either both set or
-- neither. A claim older than the configured lease is treated as abandoned,
-- which is what makes a crashed dispatcher recover without a second live
-- dispatcher double-sending.
--
-- next_attempt_at + attempt_count hold the retry state for transient send
-- failures: the dispatcher releases the claim and parks the row until the
-- exponential backoff fires (min(2^n * 15s, 10min)). A row still undelivered
-- at send_at + 24h is dead-lettered as FAILED/FAILED_RETRY_EXHAUSTED.
--
-- sent_message_id points at the delivered row once SENT. It is SET NULL so
-- purging old messages cannot cascade-remove the audit row.

CREATE TABLE scheduled_messages (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id   UUID         NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_message_id UUID         NOT NULL,
    body              TEXT         NOT NULL,
    send_at           TIMESTAMPTZ  NOT NULL,
    status            VARCHAR(8)   NOT NULL DEFAULT 'PENDING'
                                   CHECK (status IN ('PENDING', 'SENT', 'CANCELED', 'FAILED')),
    failure_code      VARCHAR(40),
    sent_message_id   UUID         REFERENCES messages(id) ON DELETE SET NULL,
    claimed_at        TIMESTAMPTZ,
    claim_token       UUID,
    next_attempt_at   TIMESTAMPTZ,
    attempt_count     INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT scheduled_sender_client_unique UNIQUE (sender_id, client_message_id),
    CONSTRAINT scheduled_body_length
        CHECK (status <> 'PENDING' OR char_length(btrim(body)) BETWEEN 1 AND 10000),
    CONSTRAINT scheduled_claim_pair
        CHECK ((claimed_at IS NULL) = (claim_token IS NULL)),
    CONSTRAINT scheduled_failure_code
        CHECK ((status <> 'FAILED' OR failure_code IS NOT NULL)
               AND (status IN ('FAILED', 'CANCELED') OR failure_code IS NULL))
);

-- The dispatcher's due scan: oldest due unclaimed/expired-claim rows first.
CREATE INDEX idx_scheduled_due ON scheduled_messages (send_at, id)
    WHERE status = 'PENDING';

-- The author's board: per-conversation PENDING rows for one sender, and the
-- 50-pending-per-sender ceiling count.
CREATE INDEX idx_scheduled_conv_sender ON scheduled_messages (conversation_id, sender_id, send_at)
    WHERE status = 'PENDING';

-- Earliest lease-expiry lookup for the scheduler's next-wake query.
CREATE INDEX idx_scheduled_claim_lease ON scheduled_messages (claimed_at, id)
    WHERE status = 'PENDING' AND claimed_at IS NOT NULL;

-- Earliest retry wake-up lookup for the scheduler's next-wake query.
CREATE INDEX idx_scheduled_retry ON scheduled_messages (next_attempt_at, id)
    WHERE status = 'PENDING' AND next_attempt_at IS NOT NULL;

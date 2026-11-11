-- Supports earliest lease-expiry lookup in the event-driven reminder scheduler.
CREATE INDEX idx_reminders_claim_lease ON conversation_reminders (claimed_at, id)
    WHERE fired_at IS NULL AND claimed_at IS NOT NULL;

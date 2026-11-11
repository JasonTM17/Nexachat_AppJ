ALTER TABLE users
    ADD COLUMN ai_summary_consent_epoch BIGINT NOT NULL DEFAULT 0
        CHECK (ai_summary_consent_epoch >= 0);

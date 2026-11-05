ALTER TABLE users
    ADD COLUMN ai_summary_consent BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE message_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    from_seq BIGINT NOT NULL,
    to_seq BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    provider VARCHAR(8) NOT NULL CHECK (provider IN ('LOCAL', 'LLM')),
    language VARCHAR(8) NOT NULL CHECK (language IN ('AUTO', 'VI', 'EN')),
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_message_summaries_sequence CHECK (from_seq <= to_seq),
    CONSTRAINT uq_message_summaries_window UNIQUE
        (conversation_id, from_seq, to_seq, content_hash, provider, language)
);

CREATE INDEX idx_message_summaries_expires ON message_summaries (expires_at);

CREATE TABLE summary_request_limits (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    window_type VARCHAR(8) NOT NULL CHECK (window_type IN ('MINUTE', 'DAY')),
    window_start TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (user_id, window_type, window_start)
);

CREATE INDEX idx_summary_request_limits_window ON summary_request_limits (window_type, window_start);

-- Conversation RAG: message embedding index, ask answer cache and ask rate limits.
--
-- One embedding row per message. Vectors are float32 little-endian; the byte
-- length is pinned to dim * 4 so a row can never carry a truncated or
-- over-long vector. The model column identifies the embedding space: a
-- conversation may be indexed by the offline lexical provider or by a
-- configured external provider, and readers only reuse a vector whose model
-- matches the provider they just used.
--
-- The ask cache stores the question vector (for semantic lookup) and the
-- validated answer payload. cited_message_ids plus cited_content_hash let a
-- cache hit prove the cited messages are unchanged and not deleted before the
-- cached answer is replayed.

CREATE TABLE message_embeddings (
    message_id     UUID PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    model          VARCHAR(64) NOT NULL,
    dim            INTEGER     NOT NULL CHECK (dim > 0 AND dim <= 1024),
    vector         BYTEA       NOT NULL CHECK (octet_length(vector) = dim * 4),
    content_hash   CHAR(64)    NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_embeddings_conversation
    ON message_embeddings (conversation_id, message_id);

CREATE INDEX idx_message_embeddings_model ON message_embeddings (model);

CREATE TABLE rag_ask_cache (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id    UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    model              VARCHAR(64) NOT NULL,
    question_hash      CHAR(64) NOT NULL,
    question_vector    BYTEA    NOT NULL,
    language           VARCHAR(8) NOT NULL CHECK (language IN ('AUTO', 'VI', 'EN')),
    payload_json       JSONB    NOT NULL,
    cited_message_ids  UUID[]   NOT NULL,
    cited_content_hash CHAR(64) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_rag_ask_cache_question UNIQUE (conversation_id, model, question_hash)
);

CREATE INDEX idx_rag_ask_cache_expiry ON rag_ask_cache (expires_at);
CREATE INDEX idx_rag_ask_cache_conversation ON rag_ask_cache (conversation_id, model);

CREATE TABLE rag_ask_request_limits (
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    window_type   VARCHAR(8) NOT NULL CHECK (window_type IN ('MINUTE', 'DAY')),
    window_start  TIMESTAMPTZ NOT NULL,
    request_count INTEGER     NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (user_id, window_type, window_start)
);

CREATE INDEX idx_rag_ask_request_limits_window
    ON rag_ask_request_limits (window_type, window_start);

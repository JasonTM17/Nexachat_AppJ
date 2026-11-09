-- Per-conversation AI opt-out.
--
-- A member can stop their own messages from being sent to external AI
-- providers (RAG embeddings/answers and the summary LLM) in one conversation.
-- The row is independent of membership: leaving a conversation keeps the
-- member's prior messages opted out until the member clears it (or is deleted).

CREATE TABLE conversation_ai_opt_outs (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_conversation_ai_opt_outs_user
    ON conversation_ai_opt_outs (user_id);

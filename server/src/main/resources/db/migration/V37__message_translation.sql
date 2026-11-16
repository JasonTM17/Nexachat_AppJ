-- V37: AI message translation.
--
-- Cache rows are keyed by the message's sync-v2 revision (change_version), the
-- target language and a hash of the caller's glossary, so an edit or a
-- glossary change makes prior rows unreachable without a backfill. Rows are
-- also deleted eagerly on edit/recall (see the cache-invalidation listener)
-- and expire on a TTL; per-message language diversity is capped at write
-- time so one hot message cannot grow the table unboundedly.
--
-- The glossary is per member per conversation: each member can pin terms to
-- keep verbatim (replacement NULL) or to a fixed rendering, independently of
-- what other members store.
--
-- Auto-translate is a caller-scoped preference per conversation, mirroring
-- mute/archive/ai-opt-out. translate_target_lang on users is the member-wide
-- default; when NULL the client supplies its app-language default.

ALTER TABLE users
    ADD COLUMN translate_target_lang VARCHAR(8);

CREATE TABLE conversation_translate_prefs (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auto_translate  BOOLEAN NOT NULL DEFAULT FALSE,
    target_lang     VARCHAR(8),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_conversation_translate_prefs_user
    ON conversation_translate_prefs (user_id);

CREATE TABLE translation_glossary_terms (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    term            VARCHAR(100) NOT NULL,
    -- NULL keeps the term verbatim in the output; non-NULL is a fixed rendering.
    replacement     VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id, term),
    CONSTRAINT ck_translation_glossary_term CHECK (btrim(term) <> ''),
    CONSTRAINT ck_translation_glossary_replacement
        CHECK (replacement IS NULL OR btrim(replacement) <> '')
);

CREATE TABLE message_translations (
    message_id      UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    change_version  BIGINT NOT NULL,
    target_lang     VARCHAR(8) NOT NULL,
    glossary_hash   CHAR(64) NOT NULL,
    source_lang     VARCHAR(8) NOT NULL,
    translated_text TEXT NOT NULL,
    model           VARCHAR(128),
    provider        VARCHAR(16) NOT NULL CHECK (provider IN ('primary', 'fallback', 'local')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (message_id, change_version, target_lang, glossary_hash)
);

CREATE INDEX idx_message_translations_expiry
    ON message_translations (expires_at);

CREATE TABLE translate_request_limits (
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    window_type   VARCHAR(8) NOT NULL CHECK (window_type IN ('MINUTE', 'DAY')),
    window_start  TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (user_id, window_type, window_start)
);

CREATE INDEX idx_translate_request_limits_window
    ON translate_request_limits (window_type, window_start);

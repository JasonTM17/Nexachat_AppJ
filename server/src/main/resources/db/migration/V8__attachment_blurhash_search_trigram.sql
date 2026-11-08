-- V8: attachment blur hash and a trigram index for message search.
--
-- blur_hash: optional client-supplied BlurHash placeholder for image
-- attachments (6..64 chars, base83 alphabet). The length is enforced here as
-- a last line of defence; the alphabet and the image-only rule are validated
-- in the application layer.
--
-- The search predicate is unaccent(content) ILIKE unaccent(pattern). pg_trgm
-- cannot index a STABLE expression, so an IMMUTABLE wrapper pins the
-- dictionary and the GIN index is built on that wrapper. The repository query
-- must call the wrapper too, or the planner cannot match the index expression.
--
-- The index is composite (conversation_id, unaccent(content)) so a single
-- index scan serves the conversation-scoped predicate instead of a BitmapAnd
-- of a global trigram index with the conversation btree.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;

ALTER TABLE message_attachments
    ADD COLUMN blur_hash VARCHAR(64) NULL,
    ADD CONSTRAINT message_attachments_blur_hash_length
        CHECK (blur_hash IS NULL OR length(blur_hash) BETWEEN 6 AND 64);

CREATE OR REPLACE FUNCTION public.f_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

CREATE INDEX idx_msg_conv_content_trgm ON messages
    USING gin (conversation_id uuid_ops,
               public.f_unaccent(content) gin_trgm_ops);

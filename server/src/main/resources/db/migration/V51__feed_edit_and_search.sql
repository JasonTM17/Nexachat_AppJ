-- V51: public feed post/comment editing and trigram body search.
--
-- edited_at is nullable and starts NULL on all existing rows; clients render
-- an "(edited)" indicator only when non-null.
--
-- idx_public_posts_body_trgm supports accent- and case-insensitive substring
-- search over live post bodies using the immutable public.f_unaccent wrapper
-- introduced in V8 (pg_trgm + unaccent already installed in V1/V4/V8).

ALTER TABLE public_posts ADD COLUMN edited_at TIMESTAMPTZ;
ALTER TABLE public_comments ADD COLUMN edited_at TIMESTAMPTZ;

CREATE INDEX idx_public_posts_body_trgm ON public_posts
    USING gin (public.f_unaccent(body) gin_trgm_ops)
    WHERE deleted_at IS NULL AND hidden_at IS NULL AND body IS NOT NULL;

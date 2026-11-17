-- V43: public feed.
--
-- The feed is plaintext by design and lives in its own tables: nothing here
-- joins messages or conversations, so an E2EE chat can never leak into it.
--
-- public_images is an upload-first, bind-at-post table. An image row is
-- created by POST /feed/images and is readable only through the feed: the
-- serving endpoint requires the image to be bound to a post the caller can
-- see, or the caller to be its owner (compose preview). Unbound rows older
-- than the retention window are swept together with their stored object.
--
-- public_posts carries both soft-delete (deleted_at, the author's own action)
-- and staff hide (hidden_at/by/reason). Both make the row invisible to
-- everyone except staff. client_post_id makes a retried create idempotent per
-- author. comment_count is maintained by the comment write path so the feed
-- page never counts rows per post.
--
-- public_comments is flat (no replies) and follows the same delete/hide rules.
--
-- public_post_reactions reuses the message-reaction emoji normalizer and the
-- same 16-character column width; the composite primary key makes PUT/DELETE
-- idempotent without a read-modify-write.
--
-- reports gains the two feed target types so the existing report module and
-- its OPEN-report dedupe cover posts and comments.

CREATE TABLE public_images (
    id          UUID         PRIMARY KEY,
    owner_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    mime_type   VARCHAR(100) NOT NULL,
    size_bytes  BIGINT       NOT NULL CHECK (size_bytes >= 0),
    width       INTEGER      CHECK (width IS NULL OR width >= 0),
    height      INTEGER      CHECK (height IS NULL OR height >= 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    bound_at    TIMESTAMPTZ
);

-- The sweep only ever looks at unbound uploads.
CREATE INDEX idx_public_images_unbound ON public_images (created_at) WHERE bound_at IS NULL;
CREATE INDEX idx_public_images_owner ON public_images (owner_id);

CREATE TABLE public_posts (
    id             UUID         PRIMARY KEY,
    author_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_post_id UUID         NOT NULL,
    body           VARCHAR(1000),
    image_id       UUID         REFERENCES public_images(id) ON DELETE SET NULL,
    comment_count  INTEGER      NOT NULL DEFAULT 0 CHECK (comment_count >= 0),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    hidden_at      TIMESTAMPTZ,
    hidden_by      UUID,
    hidden_reason  VARCHAR(500),
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT uq_public_posts_client UNIQUE (author_id, client_post_id),
    CONSTRAINT public_posts_body_or_image CHECK (body IS NOT NULL OR image_id IS NOT NULL)
);

-- The feed page: newest first, keyset on (created_at, id), visible rows only.
CREATE INDEX idx_public_posts_feed ON public_posts (created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND hidden_at IS NULL;
CREATE INDEX idx_public_posts_author ON public_posts (author_id, created_at DESC);
CREATE INDEX idx_public_posts_purge ON public_posts (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_public_posts_hidden ON public_posts (hidden_at) WHERE hidden_at IS NOT NULL;

CREATE TABLE public_comments (
    id                UUID         PRIMARY KEY,
    post_id           UUID         NOT NULL REFERENCES public_posts(id) ON DELETE CASCADE,
    author_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_comment_id UUID         NOT NULL,
    body              VARCHAR(500) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    hidden_at         TIMESTAMPTZ,
    hidden_by         UUID,
    hidden_reason     VARCHAR(500),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT uq_public_comments_client UNIQUE (author_id, client_comment_id)
);

-- Oldest first under one post, keyset on (created_at, id).
CREATE INDEX idx_public_comments_post ON public_comments (post_id, created_at, id);
CREATE INDEX idx_public_comments_author ON public_comments (author_id);
CREATE INDEX idx_public_comments_purge ON public_comments (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_public_comments_hidden ON public_comments (hidden_at) WHERE hidden_at IS NOT NULL;

CREATE TABLE public_post_reactions (
    post_id    UUID        NOT NULL REFERENCES public_posts(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji      VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id, emoji)
);

CREATE INDEX idx_public_post_reactions_user ON public_post_reactions (user_id);

ALTER TABLE reports DROP CONSTRAINT IF EXISTS reports_target_type_check;
ALTER TABLE reports ADD CONSTRAINT reports_target_type_check
    CHECK (target_type IN ('USER', 'MESSAGE', 'CONVERSATION', 'POST', 'COMMENT'));

-- V50: runtime Social feature controls and the live announcement CMS.
--
-- One singleton row carries the two runtime switches and the bilingual
-- announcement drafts. web_enabled gates only the /api/social/v1 web alias —
-- the legacy /api/v1/feed routes used by Android are never affected.
-- video_uploads_enabled gates new video uploads and new video binds on both
-- aliases; existing videos stay readable under the normal feed rules.
--
-- revision is the optimistic-concurrency token: every admin mutation is a
-- conditional UPDATE ... WHERE revision = :expected, so two concurrent
-- editors can never silently overwrite each other. Draft and published copy
-- are separate column sets: saving a draft never changes what is live, and
-- the CHECK constraints make a half-written draft or publication impossible.
-- Both switches ship disabled; turning them on is an audited admin action,
-- not a redeploy.

CREATE TABLE social_settings (
    id                      SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    revision                BIGINT       NOT NULL CHECK (revision >= 0),
    web_enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    video_uploads_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    draft_title_vi          VARCHAR(100),
    draft_body_vi           VARCHAR(500),
    draft_title_en          VARCHAR(100),
    draft_body_en           VARCHAR(500),
    published_title_vi      VARCHAR(100),
    published_body_vi       VARCHAR(500),
    published_title_en      VARCHAR(100),
    published_body_en       VARCHAR(500),
    published_at            TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT social_settings_draft_complete CHECK (
        (draft_title_vi IS NULL AND draft_body_vi IS NULL
            AND draft_title_en IS NULL AND draft_body_en IS NULL)
        OR (draft_title_vi IS NOT NULL AND draft_body_vi IS NOT NULL
            AND draft_title_en IS NOT NULL AND draft_body_en IS NOT NULL)
    ),
    CONSTRAINT social_settings_published_complete CHECK (
        (published_title_vi IS NULL AND published_body_vi IS NULL
            AND published_title_en IS NULL AND published_body_en IS NULL
            AND published_at IS NULL)
        OR (published_title_vi IS NOT NULL AND published_body_vi IS NOT NULL
            AND published_title_en IS NOT NULL AND published_body_en IS NOT NULL
            AND published_at IS NOT NULL)
    )
);

INSERT INTO social_settings (id, revision) VALUES (1, 1);

-- V15: group polls.
--
-- A poll belongs to one GROUP conversation and holds 2..10 ordered options.
-- Votes are keyed by (poll, option, user): the primary key is the final
-- uniqueness guard against a double count. Single-choice replacement and
-- multiple-choice set replacement are serialized by a FOR UPDATE lock on the
-- polls row in PollService, because the single-choice invariant spans rows
-- and cannot be expressed as a table constraint without duplicating the poll
-- mode into every vote.
--
-- poll_version_seq supplies the monotonic revision used by the poll change
-- feed. Every state change consumes one value, so (conversation_id, version)
-- can be read in ascending order by a client that has no WebSocket event.

CREATE SEQUENCE poll_version_seq;

CREATE TABLE polls (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    created_by      UUID        NOT NULL REFERENCES users(id),
    question        VARCHAR(200) NOT NULL CHECK (btrim(question) <> ''),
    multiple_choice BOOLEAN     NOT NULL DEFAULT FALSE,
    anonymous       BOOLEAN     NOT NULL DEFAULT FALSE,
    closes_at       TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    closed_by       UUID        REFERENCES users(id),
    deleted_at      TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT nextval('poll_version_seq'),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT polls_closed_by_consistent
        CHECK ((closed_at IS NULL) = (closed_by IS NULL))
);

CREATE INDEX idx_polls_conversation_created
    ON polls (conversation_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_polls_conversation_version
    ON polls (conversation_id, version);

CREATE TABLE poll_options (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id  UUID         NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    position INT          NOT NULL CHECK (position >= 0 AND position < 10),
    label    VARCHAR(100) NOT NULL CHECK (btrim(label) <> ''),
    CONSTRAINT poll_options_position_key UNIQUE (poll_id, position)
);

CREATE TABLE poll_votes (
    poll_id    UUID        NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    option_id  UUID        NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (poll_id, option_id, user_id)
);

CREATE INDEX idx_poll_votes_poll_user ON poll_votes (poll_id, user_id);

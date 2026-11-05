-- V4: privacy-aware single-node user presence and literal message search.
CREATE EXTENSION IF NOT EXISTS unaccent;

ALTER TABLE users
    ADD COLUMN last_seen_at TIMESTAMPTZ,
    ADD COLUMN show_presence BOOLEAN NOT NULL DEFAULT TRUE;

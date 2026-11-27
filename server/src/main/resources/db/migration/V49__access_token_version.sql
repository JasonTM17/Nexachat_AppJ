ALTER TABLE users
    ADD COLUMN access_token_version BIGINT NOT NULL DEFAULT 0
        CHECK (access_token_version >= 0);

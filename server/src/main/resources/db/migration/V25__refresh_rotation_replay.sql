ALTER TABLE refresh_tokens
    ADD COLUMN replay_key_digest CHAR(64),
    ADD COLUMN replay_ciphertext BYTEA,
    ADD COLUMN replay_nonce BYTEA,
    ADD COLUMN replay_rotated_at TIMESTAMPTZ,
    ADD COLUMN replay_until TIMESTAMPTZ,
    ADD COLUMN replay_invalidated BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT ck_refresh_replay_digest
        CHECK (replay_key_digest IS NULL OR length(btrim(replay_key_digest)) = 64),
    ADD CONSTRAINT ck_refresh_replay_payload
        CHECK ((replay_ciphertext IS NULL AND replay_nonce IS NULL)
            OR (replay_ciphertext IS NOT NULL AND replay_nonce IS NOT NULL
                AND octet_length(replay_nonce) = 12)),
    ADD CONSTRAINT ck_refresh_replay_times
        CHECK ((replay_key_digest IS NULL AND replay_rotated_at IS NULL AND replay_until IS NULL)
            OR (replay_key_digest IS NOT NULL AND replay_rotated_at IS NOT NULL
                AND replay_until > replay_rotated_at)),
    ADD CONSTRAINT ck_refresh_replay_requires_rotation
        CHECK (replay_key_digest IS NULL OR (revoked_at IS NOT NULL AND replaced_by IS NOT NULL));

CREATE INDEX idx_refresh_replay_cleanup
    ON refresh_tokens (replay_until, id)
    WHERE replay_ciphertext IS NOT NULL;

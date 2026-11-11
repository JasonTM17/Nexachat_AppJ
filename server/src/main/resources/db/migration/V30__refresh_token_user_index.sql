-- V30: user-scoped refresh-token reads must not scan the whole token table.
--
-- Token lookup by hash is served by the unique token_hash index, but two
-- user-scoped paths had no usable index: the password-change path lists every
-- token of the caller with ORDER BY id FOR UPDATE, and logout-all plus account
-- deletion invalidate the caller's replay payloads. The V1 index
-- idx_refresh_user is partial on revoked_at IS NULL, so neither query (which
-- must see revoked predecessors) can use it. On a 200k-row token table both
-- did a full sequential scan (index-only run: ~29 ms and ~26 ms, 5,044 shared
-- buffers each).
--
-- A full (user_id, id) index serves all three user-scoped statements; the
-- live-only revocation filters revoked_at over the caller's few rows instead
-- of using the wider partial index alone, so keeping both would only add write
-- cost to every rotation. Replace the partial index.
DROP INDEX IF EXISTS idx_refresh_user;

CREATE INDEX idx_refresh_user_id ON refresh_tokens (user_id, id);

-- V33: Google-only accounts may have NULL phone and NULL password_hash.
-- Active rows still need a sign-in anchor; verified email covers Google-only users
-- because external identities cannot be enforced in SQL.

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users ADD CONSTRAINT users_active_has_sign_in_anchor CHECK (
    deleted_at IS NOT NULL
    OR phone IS NOT NULL
    OR password_hash IS NOT NULL
    OR email_verified_at IS NOT NULL
);

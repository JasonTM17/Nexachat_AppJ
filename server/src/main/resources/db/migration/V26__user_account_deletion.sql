-- V26: soft-delete accounts and free the E.164 phone for re-registration.
-- Active phones stay unique; tombstoned rows hold NULL phone.

ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;

ALTER TABLE users DROP CONSTRAINT users_phone_key;
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;

CREATE UNIQUE INDEX users_phone_active_key
    ON users (phone)
    WHERE deleted_at IS NULL AND phone IS NOT NULL;

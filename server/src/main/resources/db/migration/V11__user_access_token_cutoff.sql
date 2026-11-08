-- V11: per-user access-token cutoff.
--
-- Logout, logout-all and password change stamp this column with the instant
-- they ran. The JWT filter rejects any access token whose issued-at is not
-- after the cutoff, so an access token that was already issued stops working
-- immediately instead of living out its full 15-minute lifetime after the
-- user logged out or rotated the password.
--
-- NULL means "no cutoff": every access token issued so far stays valid, which
-- is the state of accounts that have never logged out or changed their
-- password. The lookup is by primary key, so no extra index is needed.

ALTER TABLE users ADD COLUMN tokens_valid_after TIMESTAMPTZ;

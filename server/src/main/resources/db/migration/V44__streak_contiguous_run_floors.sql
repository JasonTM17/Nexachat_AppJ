-- V44: per-side contiguous-run floors on the streak tables.
--
-- A streak day completes only when BOTH sides sent on it. Until now that
-- required both stored side days to sit exactly on the day, so a record that
-- arrived late (its side's stored day already moved past it) could never
-- complete the day — most visibly when one member's send straddles local
-- midnight while the peer's earlier send waits on the conversation lock.
--
-- low_cont/high_cont (open_cont/sent_cont on user_streaks) record the first
-- day of each side's current contiguous run of sends: every day in
-- [cont, day] was provably sent by that side. A day now completes when it lies
-- in (last_day, min(low_day, high_day)] and is at or above both floors.
--
-- The backfill is deliberately conservative: a run is only known to be
-- contiguous from the current pointer, so the floor starts there and recovery
-- applies to runs recorded from this migration onward. NULL (the side never
-- sent, or a row written by code that did not maintain the floor) reads as
-- "unknown" and behaves exactly like the pointer, i.e. pre-V44 behavior.
-- The columns are additive and nullable, so the migration is safe on a live
-- table with no rewrite of existing rows beyond setting the floor.

ALTER TABLE conversation_streaks
    ADD COLUMN low_cont  DATE,
    ADD COLUMN high_cont DATE;

ALTER TABLE user_streaks
    ADD COLUMN open_cont DATE,
    ADD COLUMN sent_cont DATE;

UPDATE conversation_streaks SET low_cont  = low_day  WHERE low_day  IS NOT NULL;
UPDATE conversation_streaks SET high_cont = high_day WHERE high_day IS NOT NULL;
UPDATE user_streaks      SET open_cont  = open_day WHERE open_day  IS NOT NULL;
UPDATE user_streaks      SET sent_cont  = sent_day WHERE sent_day  IS NOT NULL;

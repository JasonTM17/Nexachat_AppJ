-- V35: repair rows inserted bypassing the sync-v2 change-cursor contract
-- (e.g. an older demo/seed.sql that left messages.change_version = 0).
-- Sync-v2 clients drop messages with change_version <= 0, so such rows were
-- invisible on devices even though they existed in the database.
--
-- Unversioned rows are assigned revisions continuing after the highest
-- versioned revision already present in their conversation, ordered by
-- (seq, id) to preserve the existing timeline. conversations.change_version
-- is then raised to cover every message. Healthy databases are unaffected:
-- with no unversioned rows the messages UPDATE matches nothing, and the
-- conversations UPDATE only touches rows whose cursor needs a raise.
--
-- Known edge, kept deliberately: when a conversation's cursor is already
-- ahead of every message revision (e.g. retention deleted the newest
-- messages while unversioned rows remained), repaired rows receive revisions
-- below that cursor. Clients already synced past it will not re-pull them
-- until a resync — uniqueness and the cv <= cursor contract are still
-- restored. Changing that would mean basing revisions on the cursor instead.

-- Lock the affected conversation rows first. Writers allocate revisions via
-- UPDATE conversations SET change_version = change_version + 1, which takes
-- the same row lock, so a send concurrent with this backfill serializes
-- behind it instead of taking a revision the backfill is about to assign.
SELECT id
FROM conversations
WHERE id IN (SELECT DISTINCT conversation_id FROM messages WHERE change_version <= 0)
ORDER BY id
FOR UPDATE;

WITH ranked_unversioned AS (
    SELECT m.id,
           m.conversation_id,
           COALESCE(base.max_revision, 0)
               + row_number() OVER (
                     PARTITION BY m.conversation_id
                     ORDER BY m.seq, m.id
                 ) AS revision
    FROM messages m
    LEFT JOIN (
        SELECT conversation_id, MAX(change_version) AS max_revision
        FROM messages
        WHERE change_version > 0
        GROUP BY conversation_id
    ) base ON base.conversation_id = m.conversation_id
    WHERE m.change_version <= 0
)
UPDATE messages m
SET change_version = ranked_unversioned.revision
FROM ranked_unversioned
WHERE ranked_unversioned.id = m.id;

UPDATE conversations c
SET change_version = GREATEST(c.change_version, COALESCE((
        SELECT MAX(m.change_version)
        FROM messages m
        WHERE m.conversation_id = c.id
    ), 0))
WHERE c.change_version < COALESCE((
        SELECT MAX(m.change_version)
        FROM messages m
        WHERE m.conversation_id = c.id
    ), 0);

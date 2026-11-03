-- V2: attachments become upload-first, bind-at-send (phase-09 multimedia).
--
-- Before V2, message_attachments.message_id was NOT NULL, so a row could only
-- exist already bound to a message — there was no way to upload a file before
-- sending. V2 flips the lifecycle to match UC-09 (POST /api/v1/attachments
-- first, then send with attachmentIds):
--
--   1. Upload inserts a row with owner_id = uploader and message_id = NULL.
--   2. MessageService.send() validates each attachmentId (exists + owned by
--      the sender + still unbound) and sets message_id in the same tx.
--
-- owner_id is NOT NULL with a FK to users; pre-V2 rows (always bound at
-- insert) backfill their owner from the bound message's sender.

ALTER TABLE message_attachments
    ADD COLUMN owner_id UUID;

UPDATE message_attachments a
   SET owner_id = m.sender_id
  FROM messages m
 WHERE a.message_id = m.id;

ALTER TABLE message_attachments
    ALTER COLUMN owner_id SET NOT NULL,
    ADD CONSTRAINT message_attachments_owner_id_fkey
        FOREIGN KEY (owner_id) REFERENCES users(id),
    ALTER COLUMN message_id DROP NOT NULL;

-- Uploader lookup: a sender's own unbound uploads / per-user file inventory.
CREATE INDEX idx_att_owner ON message_attachments (owner_id);

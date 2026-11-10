-- V24: hot-path maintenance and inbox query hygiene.
--
-- idx_conv_inbox (conversations.last_message_seq DESC NULLS LAST) was unused in
-- every audited read path: the inbox always filters through conversation_members
-- and sorts with pinned/activity/cursor tie-breakers. Dropping it removes per-
-- send maintenance on conversations.advanceLastMessage with no plan change.
DROP INDEX IF EXISTS idx_conv_inbox;

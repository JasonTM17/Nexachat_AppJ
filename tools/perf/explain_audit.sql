-- EXPLAIN (ANALYZE, BUFFERS) audit of the hot read paths against a populated
-- nexachat_perf. Run with:
--   psql -h localhost -p 5433 -U nexachat -d nexachat_perf -f tools/perf/explain_audit.sql
--
-- The two bind values are the busiest user and the largest conversation in the
-- seed; they are overwritten by the seed's deterministic ids.

\set hot_user 'bc8435b4-a192-222c-d55a-1f39e4ebb9c2'
-- The busiest user (61 conversations) and the largest conversation (Group 1,
-- 8000 messages, 200 polls, 20k concentrated reaction events).
\set hot_conv 'c8f18785-c339-59aa-f68e-ff15741fe660'

SELECT max(seq) AS hot_max_seq FROM messages WHERE conversation_id = :'hot_conv' \gset

SET track_io_timing = on;

\echo '=== 1. inbox list (findInboxConversations)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id, c.type, c.title, c.avatar_url, c.last_message_id, c.last_message_seq,
       c.created_at, m.last_read_seq, m.muted, m.muted_until, m.archived, m.pinned, m.pinned_at
FROM conversation_members m
JOIN conversations c ON c.id = m.conversation_id
WHERE m.user_id = :'hot_user' AND m.archived = false
ORDER BY m.pinned DESC, COALESCE(c.last_message_seq, 0) DESC, c.created_at DESC, c.id ASC;

\echo '=== 2. inbox member views (findInboxMemberViews)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.conversation_id, u.id, u.display_name, u.phone, u.avatar_url, m.role
FROM conversation_members m
JOIN users u ON u.id = m.user_id
WHERE m.conversation_id IN (
    SELECT conversation_id FROM conversation_members WHERE user_id = :'hot_user'
)
ORDER BY u.id;

\echo '=== 3. unread counts (countUnreadByConversation)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.conversation_id, COUNT(msg)
FROM conversation_members m, messages msg
WHERE m.user_id = :'hot_user'
  AND m.conversation_id IN (
      SELECT conversation_id FROM conversation_members WHERE user_id = :'hot_user'
  )
  AND msg.conversation_id = m.conversation_id
  AND msg.seq > m.last_read_seq
GROUP BY m.conversation_id;

\echo '=== 4. direct peer presence (findDirectPeerPresence)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT own.conversation_id, peer.user_id, u.show_presence, u.last_seen_at
FROM conversation_members own
JOIN conversation_members peer ON peer.conversation_id = own.conversation_id
JOIN users u ON u.id = peer.user_id
JOIN conversations c ON c.id = own.conversation_id
WHERE own.user_id = :'hot_user'
  AND c.type = 'DIRECT'
  AND own.conversation_id IN (
      SELECT conversation_id FROM conversation_members WHERE user_id = :'hot_user'
  )
  AND peer.user_id <> :'hot_user';

\echo '=== 5. archive MEDIA page (findMediaPage)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.seq, m.sender_id, m.created_at,
       a.id, a.original_name, a.mime_type, a.size_bytes,
       a.duration_ms, a.blur_hash, a.width, a.height
FROM message_attachments a
JOIN messages m ON m.id = a.message_id
WHERE m.conversation_id = :'hot_conv'
  AND m.deleted_at IS NULL
  AND m.type <> 'AUDIO'
  AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
ORDER BY m.seq DESC, a.id ASC
LIMIT 31;

\echo '=== 6. archive FILE page (findFilePage)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.seq, m.sender_id, m.created_at,
       a.id, a.original_name, a.mime_type, a.size_bytes,
       a.duration_ms, a.blur_hash, a.width, a.height
FROM message_attachments a
JOIN messages m ON m.id = a.message_id
WHERE m.conversation_id = :'hot_conv'
  AND m.deleted_at IS NULL
  AND m.type <> 'AUDIO'
  AND a.mime_type NOT LIKE 'image/%'
  AND a.mime_type NOT LIKE 'video/%'
ORDER BY m.seq DESC, a.id ASC
LIMIT 31;

\echo '=== 7. archive LINK page (findLinkMessagePage)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.seq, m.sender_id, m.created_at, m.content
FROM messages m
WHERE m.conversation_id = :'hot_conv'
  AND m.deleted_at IS NULL
  AND m.type = 'TEXT'
  AND m.content IS NOT NULL
  AND m.content ~* 'https{0,1}://'
ORDER BY m.seq DESC
LIMIT 31;

\echo '=== 8. poll list first page (findVisibleFirstPage)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, p.conversation_id, p.created_by, p.question, p.multiple_choice,
       p.anonymous, p.closes_at, p.closed_at, p.closed_by, p.deleted_at,
       p.version, p.created_at
FROM polls p
WHERE p.conversation_id = :'hot_conv' AND p.deleted_at IS NULL
ORDER BY p.created_at DESC, p.id DESC
LIMIT 51;

\echo '=== 9. poll change feed (findChanges)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, p.conversation_id, p.created_by, p.question, p.multiple_choice,
       p.anonymous, p.closes_at, p.closed_at, p.closed_by, p.deleted_at,
       p.version, p.created_at
FROM polls p
WHERE p.conversation_id = :'hot_conv' AND p.version > 0
ORDER BY p.version ASC
LIMIT 101;

\echo '=== 10. poll vote rows for a page of polls (findRowsByPollIds)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT v.poll_id, v.option_id, v.user_id
FROM poll_votes v
WHERE v.poll_id IN (
    SELECT id FROM polls WHERE conversation_id = :'hot_conv' ORDER BY created_at DESC LIMIT 50
);

\echo '=== 11. reminder due claim (lockDueIds)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM conversation_reminders
WHERE fired_at IS NULL
  AND remind_at <= now()
  AND (claimed_at IS NULL
       OR claimed_at < now() - CAST(600 AS double precision) * interval '1 second')
ORDER BY remind_at ASC, id ASC
LIMIT 10
FOR UPDATE SKIP LOCKED;

\echo '=== 12. reminder upcoming board (findUpcoming)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.id, r.conversation_id, r.created_by, r.title, r.remind_at, r.anchor_at,
       r.repeat_rule, r.fired_at, r.last_fired_at, r.claimed_at, r.claim_token,
       r.created_at, r.updated_at
FROM conversation_reminders r
WHERE r.conversation_id = :'hot_conv' AND r.fired_at IS NULL AND r.remind_at > now()
ORDER BY r.remind_at ASC, r.id ASC;

\echo '=== 13. reminder active count (countActive)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*) FROM conversation_reminders r
WHERE r.conversation_id = :'hot_conv' AND r.fired_at IS NULL;

\echo '=== 14. delta sync new messages (findRowsAfter)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.conversation_id, m.seq, u.id, u.display_name, m.type, m.content,
       m.client_message_id, r.id, m.created_at, m.edited_at, m.deleted_at
FROM messages m
JOIN users u ON u.id = m.sender_id
LEFT JOIN messages r ON r.id = m.reply_to_id
WHERE m.conversation_id = :'hot_conv' AND m.seq > (:hot_max_seq - 484)
ORDER BY m.seq ASC
LIMIT 51;

\echo '=== 15. delta sync edited messages (findEditedRowsSince)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.conversation_id, m.seq, u.id, u.display_name, m.type, m.content,
       m.client_message_id, r.id, m.created_at, m.edited_at, m.deleted_at
FROM messages m
JOIN users u ON u.id = m.sender_id
LEFT JOIN messages r ON r.id = m.reply_to_id
WHERE m.conversation_id = :'hot_conv'
  AND m.seq <= :hot_max_seq
  AND m.edited_at > now() - interval '7 days'
  AND m.deleted_at IS NULL
ORDER BY m.edited_at ASC;

\echo '=== 16. delta sync deleted ids (findDeletedMessageIdsSince)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM messages m
WHERE m.conversation_id = :'hot_conv'
  AND m.seq <= :hot_max_seq
  AND m.deleted_at > now() - interval '7 days';

\echo '=== 17. delta sync max seq (findMaxSeqByConversationId)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(MAX(m.seq), 0) FROM messages m WHERE m.conversation_id = :'hot_conv';

\echo '=== 18. reaction event cursor, first page (findEventIdsSince, cursor NULL)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, message_id
FROM reaction_events
WHERE conversation_id = :'hot_conv'
  AND (CAST(NULL AS bigint) IS NOT NULL OR created_at > now() - interval '7 days')
  AND id > COALESCE(CAST(NULL AS bigint), 0)
ORDER BY id ASC
LIMIT 101;

\echo '=== 19. reaction event cursor, continuation page (findEventIdsSince, cursor set)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, message_id
FROM reaction_events
WHERE conversation_id = :'hot_conv'
  AND (CAST(1000 AS bigint) IS NOT NULL OR created_at > now() - interval '7 days')
  AND id > COALESCE(CAST(1000 AS bigint), 0)
ORDER BY id ASC
LIMIT 101;

\echo '=== 20. STOMP membership check (existsByConversation_IdAndUser_Id)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT EXISTS (
    SELECT 1 FROM conversation_members
    WHERE conversation_id = :'hot_conv' AND user_id = :'hot_user'
);

\echo '=== 21. pin ceiling count (countPinnedByUserId)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*) FROM conversation_members WHERE user_id = :'hot_user' AND pinned = true;

\echo '=== 22. muted member ids (findMutedMemberIds)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT user_id FROM conversation_members
WHERE conversation_id = :'hot_conv'
  AND muted = true
  AND (muted_until IS NULL OR muted_until > now());

\echo '=== 23. member flags (findMemberFlags)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.conversation_id, m.muted, m.muted_until, m.archived, m.pinned, m.pinned_at
FROM conversation_members m
WHERE m.user_id = :'hot_user'
  AND m.conversation_id IN (
      SELECT conversation_id FROM conversation_members WHERE user_id = :'hot_user'
  );

\echo '=== 24. inbox keyset page (findInboxConversationsPage, cursor mid-list)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id, c.type, c.title, c.avatar_url, c.last_message_id, c.last_message_seq,
       c.created_at, m.last_read_seq, m.muted, m.muted_until, m.archived, m.pinned, m.pinned_at
FROM conversation_members m
JOIN conversations c ON c.id = m.conversation_id
WHERE m.user_id = :'hot_user'
  AND m.archived = false
  AND (false
       OR (false AND m.pinned = false)
       OR (m.pinned = false
           AND (COALESCE(c.last_message_seq, 0) < 100000
                OR (COALESCE(c.last_message_seq, 0) = 100000 AND c.created_at < now() - interval '300 days')
                OR (COALESCE(c.last_message_seq, 0) = 100000 AND c.created_at = now() - interval '300 days'
                    AND c.id > :'hot_user'))))
ORDER BY m.pinned DESC, COALESCE(c.last_message_seq, 0) DESC, c.created_at DESC, c.id ASC
LIMIT 101;

\echo '=== 25. message search (searchTextRows, trigram index)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.conversation_id, m.seq, m.sender_id, u.display_name, m.type, m.content,
       m.client_message_id, reply.id, m.created_at, m.edited_at, m.deleted_at
FROM messages m
JOIN users u ON u.id = m.sender_id
LEFT JOIN messages reply ON reply.id = m.reply_to_id
WHERE m.conversation_id = :'hot_conv'
  AND m.type = 'TEXT'
  AND m.deleted_at IS NULL
  AND public.f_unaccent(m.content) ILIKE public.f_unaccent('%message 5%') ESCAPE chr(92)
ORDER BY m.seq DESC
LIMIT 21;

\echo '=== 26. reaction event retention purge (deleteOlderThan)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
DELETE FROM reaction_events WHERE created_at < now() - interval '1 day';
ROLLBACK;

-- ---------------------------------------------------------------------------
-- Generic plans. The JDBC driver server-prepares a statement after 5
-- executions, so production runs the GENERIC plan for these nullable-bind
-- queries, not the custom plan shown above. force_generic_plan reproduces it.
-- ---------------------------------------------------------------------------

\echo '=== P1. archive MEDIA page, generic plan'
PREPARE p_media (uuid, uuid, timestamptz, timestamptz, bigint, uuid, int) AS
SELECT m.id, m.seq, m.sender_id, m.created_at,
       a.id, a.original_name, a.mime_type, a.size_bytes,
       a.duration_ms, a.blur_hash, a.width, a.height
FROM message_attachments a
JOIN messages m ON m.id = a.message_id
WHERE m.conversation_id = $1
  AND m.deleted_at IS NULL
  AND m.type <> 'AUDIO'
  AND ($2 IS NULL OR m.sender_id = $2)
  AND ($3 IS NULL OR m.created_at >= $3)
  AND ($4 IS NULL OR m.created_at <= $4)
  AND ($5 IS NULL OR m.seq < $5 OR (m.seq = $5 AND a.id > $6))
  AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
ORDER BY m.seq DESC, a.id ASC
LIMIT $7;
SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE p_media (:'hot_conv', NULL, NULL, NULL, NULL, NULL, 31);
RESET plan_cache_mode;
DEALLOCATE p_media;

\echo '=== P2. archive LINK page, generic plan'
PREPARE p_link (uuid, uuid, timestamptz, timestamptz, bigint, int) AS
SELECT m.id, m.seq, m.sender_id, m.created_at, m.content
FROM messages m
WHERE m.conversation_id = $1
  AND m.deleted_at IS NULL
  AND m.type = 'TEXT'
  AND m.content IS NOT NULL
  AND m.content ~* 'https{0,1}://'
  AND ($2 IS NULL OR m.sender_id = $2)
  AND ($3 IS NULL OR m.created_at >= $3)
  AND ($4 IS NULL OR m.created_at <= $4)
  AND ($5 IS NULL OR m.seq <= $5)
ORDER BY m.seq DESC
LIMIT $6;
SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE p_link (:'hot_conv', NULL, NULL, NULL, NULL, 31);
RESET plan_cache_mode;
DEALLOCATE p_link;

\echo '=== P3. inbox keyset page, generic plan'
PREPARE p_inbox (uuid, bool, bigint, timestamptz, uuid, bool, int) AS
SELECT c.id, c.type, c.title, c.avatar_url, c.last_message_id, c.last_message_seq,
       c.created_at, m.last_read_seq, m.muted, m.muted_until, m.archived, m.pinned, m.pinned_at
FROM conversation_members m
JOIN conversations c ON c.id = m.conversation_id
WHERE m.user_id = $1
  AND m.archived = $6
  AND ($5 IS NULL
       OR ($2 = true AND m.pinned = false)
       OR (m.pinned = $2
           AND (COALESCE(c.last_message_seq, 0) < $3
                OR (COALESCE(c.last_message_seq, 0) = $3 AND c.created_at < $4)
                OR (COALESCE(c.last_message_seq, 0) = $3 AND c.created_at = $4 AND c.id > $5))))
ORDER BY m.pinned DESC, COALESCE(c.last_message_seq, 0) DESC, c.created_at DESC, c.id ASC
LIMIT $7;
SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE p_inbox (:'hot_user', false, 100000, now() - interval '300 days', :'hot_user', false, 101);
RESET plan_cache_mode;
DEALLOCATE p_inbox;

\echo '=== P4. message history page, generic plan (beforeSeq NULL)'
PREPARE p_history (uuid, bigint, int) AS
SELECT m.id, m.conversation_id, m.seq, u.id, u.display_name, m.type, m.content,
       m.client_message_id, r.id, m.created_at, m.edited_at, m.deleted_at
FROM messages m
JOIN users u ON u.id = m.sender_id
LEFT JOIN messages r ON r.id = m.reply_to_id
WHERE m.conversation_id = $1
  AND ($2 IS NULL OR m.seq < $2)
ORDER BY m.seq DESC
LIMIT $3;
SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE p_history (:'hot_conv', NULL, 31);
RESET plan_cache_mode;
DEALLOCATE p_history;

\echo '=== P5. reaction event cursor, generic plan (cursor NULL, first page)'
PREPARE p_events (uuid, timestamptz, bigint, int) AS
SELECT id, message_id
FROM reaction_events
WHERE conversation_id = $1
  AND ($3 IS NOT NULL OR created_at > $2)
  AND id > COALESCE($3, 0)
ORDER BY id ASC
LIMIT $4;
SET plan_cache_mode = force_generic_plan;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE p_events (:'hot_conv', now() - interval '7 days', NULL, 101);
RESET plan_cache_mode;
DEALLOCATE p_events;


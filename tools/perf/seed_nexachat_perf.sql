-- Seed the private query-performance database (nexachat_perf).
--
-- Applies to an empty database that already carries Flyway V1..V16 (the
-- production schema). Produces a Zipf-shaped dataset for EXPLAIN ANALYZE work:
--   ~200 users, ~2000 conversations (100 DIRECT + 1900 GROUP), ~500k messages
--   (40 hot conversations at 8000 messages each, the rest at 92), ~100k
--   attachments, 2000 polls with votes, 5000 reminders, reactions and
--   reaction events, plus mute/archive/pin member flags.
--
-- Deterministic ids (md5 of a stable key) so a re-seed is reproducible and the
-- audit queries can reference known users/conversations without a lookup.
--
-- Run:  psql -h localhost -p 5433 -U nexachat -d nexachat_perf -f seed_nexachat_perf.sql

\set ON_ERROR_STOP on
SET client_min_messages = warning;
SET maintenance_work_mem = '256MB';

-- --------------------------------------------------------------------- users
INSERT INTO users (id, phone, password_hash, display_name, created_at)
SELECT md5('user-' || g)::uuid,
       '+849' || lpad(g::text, 8, '0'),
       'x',
       'User ' || g,
       now() - ((g * 7) || ' days')::interval
FROM generate_series(1, 200) AS g;

-- -------------------------------------------------------------- conversations
-- 100 DIRECT (2 members each) + 1900 GROUP (2..9 members) = 2000.
INSERT INTO conversations (id, type, direct_key, title, created_by, created_at)
SELECT md5('conv-d-' || g)::uuid,
       'DIRECT',
       'direct:' || (2 * g - 1) || ':' || (2 * g),
       NULL,
       md5('user-' || (2 * g - 1))::uuid,
       now() - ((g * 3) || ' days')::interval
FROM generate_series(1, 100) AS g;

INSERT INTO conversations (id, type, direct_key, title, created_by, created_at)
SELECT md5('conv-g-' || g)::uuid,
       'GROUP',
       NULL,
       'Group ' || g,
       md5('user-' || ((g % 200) + 1))::uuid,
       now() - ((g * 2) || ' days')::interval
FROM generate_series(1, 1900) AS g;

-- ---------------------------------------------------------------- memberships
INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
SELECT md5('conv-d-' || g)::uuid,
       md5('user-' || v.u)::uuid,
       CASE WHEN v.u = 2 * g - 1 THEN 'ADMIN' ELSE 'MEMBER' END,
       now() - ((g * 3) || ' days')::interval
FROM generate_series(1, 100) AS g
CROSS JOIN LATERAL (VALUES (2 * g - 1), (2 * g)) AS v(u);

INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
SELECT md5('conv-g-' || g)::uuid,
       md5('user-' || ((g + k - 1) % 200 + 1))::uuid,
       CASE WHEN k = 0 THEN 'ADMIN' ELSE 'MEMBER' END,
       now() - ((g * 2) || ' days')::interval
FROM generate_series(1, 1900) AS g
CROSS JOIN LATERAL generate_series(0, 8) AS k
WHERE k < 2 + (g % 8);

-- Per-member preference flags (mute / archive / pin) on a deterministic subset.
UPDATE conversation_members
SET muted = true,
    muted_until = now() + ((hashtext(user_id::text) % 30 + 1) || ' days')::interval
WHERE hashtext(user_id::text) % 11 = 0;

UPDATE conversation_members
SET archived = true
WHERE hashtext(user_id::text) % 13 = 0;

UPDATE conversation_members
SET pinned = true,
    pinned_at = now() - ((hashtext(user_id::text) % 30) || ' days')::interval
WHERE hashtext(user_id::text) % 17 = 0;

-- ------------------------------------------------------------------- messages
-- Per-conversation message budget: the first 40 GROUP conversations are "hot".
CREATE TEMP TABLE tmp_conv_stats AS
SELECT id AS conversation_id, msg_count
FROM (
    SELECT md5('conv-d-' || g)::uuid AS id, 92 AS msg_count
    FROM generate_series(1, 100) AS g
    UNION ALL
    SELECT md5('conv-g-' || g)::uuid AS id,
           CASE WHEN g <= 40 THEN 8000 ELSE 92 END AS msg_count
    FROM generate_series(1, 1900) AS g
) AS s;

CREATE TEMP TABLE tmp_conv_member AS
SELECT conversation_id, member_no, member_count, user_id
FROM (
    SELECT conversation_id,
           user_id,
           ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY user_id) AS member_no,
           COUNT(*) OVER (PARTITION BY conversation_id) AS member_count
    FROM conversation_members
) AS s;

INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content, created_at)
SELECT s.conversation_id,
       m.user_id,
       gen_random_uuid(),
       CASE WHEN r % 10 = 0 THEN 'IMAGE'
            WHEN r % 10 = 1 THEN 'VIDEO'
            WHEN r % 10 = 2 THEN 'FILE'
            WHEN r % 10 = 3 THEN 'AUDIO'
            ELSE 'TEXT' END,
       CASE WHEN r % 10 BETWEEN 4 AND 9 AND r % 3 = 0
            THEN 'See https://example.com/page-' || r
            ELSE 'Message ' || r END,
       now() - ((s.msg_count - r) * interval '30 seconds')
FROM tmp_conv_stats AS s
CROSS JOIN LATERAL generate_series(1, s.msg_count) AS r
JOIN tmp_conv_member AS m
  ON m.conversation_id = s.conversation_id
 AND m.member_no = ((r - 1) % m.member_count) + 1;

-- A realistic share of edited and soft-deleted messages, all recently touched
-- so the delta-sync "since" windows have rows to find.
UPDATE messages
SET edited_at = now() - ((seq % 100) || ' hours')::interval
WHERE seq % 1000 = 0;

UPDATE messages
SET deleted_at = now() - ((seq % 100) || ' hours')::interval
WHERE seq % 1500 = 0;

-- Denormalized inbox pointers and per-member read/delivery watermarks.
UPDATE conversations AS c
SET last_message_id = m.id,
    last_message_seq = m.seq
FROM (
    SELECT DISTINCT ON (conversation_id) conversation_id, id, seq
    FROM messages
    ORDER BY conversation_id, seq DESC
) AS m
WHERE c.id = m.conversation_id;

UPDATE conversation_members AS cm
SET last_read_seq = CASE
        WHEN hashtext(user_id::text) % 5 = 0 THEN 0
        ELSE (COALESCE(s.max_seq, 0) * 0.7)::bigint
    END,
    last_delivered_seq = (COALESCE(s.max_seq, 0) * 0.9)::bigint
FROM (SELECT conversation_id, MAX(seq) AS max_seq FROM messages GROUP BY conversation_id) AS s
WHERE cm.conversation_id = s.conversation_id;

-- --------------------------------------------------------------- attachments
INSERT INTO message_attachments (message_id, owner_id, storage_key, original_name,
                                mime_type, size_bytes, width, height, duration_ms, blur_hash)
SELECT m.id,
       m.sender_id,
       'perf/' || m.id::text,
       'file-' || m.seq || '.bin',
       CASE m.type
           WHEN 'IMAGE' THEN 'image/png'
           WHEN 'VIDEO' THEN 'video/mp4'
           WHEN 'AUDIO' THEN 'audio/mpeg'
           ELSE 'application/pdf'
       END,
       1000 + (m.seq % 100000),
       CASE WHEN m.type = 'IMAGE' THEN 1920 END,
       CASE WHEN m.type = 'IMAGE' THEN 1080 END,
       CASE WHEN m.type IN ('VIDEO', 'AUDIO') THEN 30000 END,
       CASE WHEN m.type = 'IMAGE' THEN 'LEHV6nWB2yk8pyo0adR*.7kCMdnj' END
FROM messages AS m
WHERE m.type <> 'TEXT'
  AND m.seq % 2 = 0;

-- ----------------------------------------------- AI opt-outs and device tokens
INSERT INTO conversation_ai_opt_outs (conversation_id, user_id)
SELECT conversation_id, user_id
FROM conversation_members
WHERE hashtext(user_id::text) % 23 = 0;

INSERT INTO user_device_tokens (user_id, token, platform, device_label)
SELECT id, 'perf-token-' || id::text, 'ANDROID', 'Pixel'
FROM users
WHERE hashtext(id::text) % 2 = 0;

-- ---------------------------------------------------------------------- polls
INSERT INTO polls (id, conversation_id, created_by, question, multiple_choice, anonymous, created_at)
SELECT md5('poll-' || g)::uuid,
       c.id,
       c.created_by,
       'Poll question ' || g,
       g % 2 = 0,
       g % 3 = 0,
       now() - ((g % 200) || ' days')::interval
FROM generate_series(1, 2000) AS g
JOIN (
    SELECT id, created_by, ROW_NUMBER() OVER (ORDER BY created_at DESC) AS rn
    FROM conversations
    WHERE type = 'GROUP'
) AS c ON c.rn = CASE WHEN g <= 200 THEN 1 ELSE ((g - 200) % 1899) + 2 END;

INSERT INTO poll_options (poll_id, position, label)
SELECT md5('poll-' || g)::uuid, k, 'Option ' || k
FROM generate_series(1, 2000) AS g
CROSS JOIN LATERAL generate_series(0, 1 + (g % 4)) AS k;

-- One vote per (poll, member) on the first option the member is eligible for.
INSERT INTO poll_votes (poll_id, option_id, user_id)
SELECT poll_id, option_id, user_id
FROM (
    SELECT p.id AS poll_id,
           o.id AS option_id,
           m.user_id,
           ROW_NUMBER() OVER (PARTITION BY p.id, m.user_id ORDER BY o.position) AS rn
    FROM polls AS p
    JOIN poll_options AS o ON o.poll_id = p.id
    JOIN conversation_members AS m ON m.conversation_id = p.conversation_id
) AS v
WHERE rn = 1;

-- ------------------------------------------------------------------ reminders
INSERT INTO conversation_reminders (id, conversation_id, created_by, title,
                                    remind_at, anchor_at, repeat_rule,
                                    fired_at, last_fired_at, claimed_at, claim_token)
SELECT md5('reminder-' || g)::uuid,
       c.id,
       c.created_by,
       'Reminder ' || g,
       CASE WHEN g % 2 = 0 THEN now() - ((g % 96) || ' hours')::interval
            ELSE now() + ((g % 96) || ' hours')::interval END,
       now() - ((g % 30) || ' days')::interval,
       CASE g % 4 WHEN 0 THEN 'DAILY' WHEN 1 THEN 'WEEKLY'
                   WHEN 2 THEN 'MONTHLY' ELSE 'NONE' END,
       CASE WHEN g % 10 = 0 THEN now() - ((g % 24) || ' hours')::interval END,
       CASE WHEN g % 10 = 0 THEN now() - ((g % 24) || ' hours')::interval END,
       CASE WHEN g % 5 = 0 THEN now() - ((g % 60) || ' minutes')::interval END,
       CASE WHEN g % 5 = 0 THEN md5('claim-' || g)::uuid END
FROM generate_series(1, 5000) AS g
JOIN (
    SELECT id, created_by, ROW_NUMBER() OVER (ORDER BY created_at) AS rn
    FROM conversations
) AS c ON c.rn = (g % 2000) + 1;

-- --------------------------------------------------- reactions and their events
INSERT INTO message_reactions (message_id, user_id, emoji)
SELECT message_id, user_id, '👍'
FROM (
    SELECT DISTINCT ON (m.id) m.id AS message_id, cm.user_id
    FROM messages AS m
    JOIN conversation_members AS cm ON cm.conversation_id = m.conversation_id
    WHERE m.seq % 25 = 0
    ORDER BY m.id, cm.user_id
) AS r;

-- Spread events across the corpus, then concentrate a heavy tail in the
-- hottest conversation so the delta cursor has a real page to walk.
INSERT INTO reaction_events (conversation_id, message_id, user_id, emoji, action, created_at)
SELECT m.conversation_id, m.id, m.sender_id, '👍',
       CASE WHEN m.g % 2 = 0 THEN 'ADD' ELSE 'REMOVE' END,
       now() - ((m.g % 1000) || ' minutes')::interval
FROM (
    SELECT id, conversation_id, sender_id,
           ROW_NUMBER() OVER (ORDER BY seq) AS g
    FROM messages
    WHERE seq % 10 = 0
) AS m;

INSERT INTO reaction_events (conversation_id, message_id, user_id, emoji, action, created_at)
SELECT m.conversation_id, m.id, m.sender_id, '🎉',
       CASE WHEN m.g % 2 = 0 THEN 'ADD' ELSE 'REMOVE' END,
       now() - ((m.g % 2880) || ' minutes')::interval
FROM (
    SELECT id, conversation_id, sender_id,
           ROW_NUMBER() OVER (ORDER BY seq) AS g
    FROM messages
    WHERE conversation_id = md5('conv-g-1')::uuid
    ORDER BY seq
    LIMIT 20000
) AS m;

-- ------------------------------------------------------------------- analyze
ANALYZE;

SELECT 'users' AS t, count(*) FROM users
UNION ALL SELECT 'conversations', count(*) FROM conversations
UNION ALL SELECT 'conversation_members', count(*) FROM conversation_members
UNION ALL SELECT 'messages', count(*) FROM messages
UNION ALL SELECT 'message_attachments', count(*) FROM message_attachments
UNION ALL SELECT 'polls', count(*) FROM polls
UNION ALL SELECT 'poll_votes', count(*) FROM poll_votes
UNION ALL SELECT 'conversation_reminders', count(*) FROM conversation_reminders
UNION ALL SELECT 'message_reactions', count(*) FROM message_reactions
UNION ALL SELECT 'reaction_events', count(*) FROM reaction_events;

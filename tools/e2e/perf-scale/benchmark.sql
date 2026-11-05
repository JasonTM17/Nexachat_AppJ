\set ON_ERROR_STOP on
\pset pager off
\timing on

BEGIN;
SET LOCAL search_path TO nexachat_qa_20261105;

DROP INDEX IF EXISTS nexachat_qa_20261105.idx_qa_perf_messages_search_trgm;
DROP FUNCTION IF EXISTS nexachat_qa_20261105.qa_immutable_unaccent(text);

DELETE FROM conversations
 WHERE direct_key LIKE 'qa-perf-20261105-%';
DELETE FROM message_attachments
 WHERE owner_id IN (SELECT id FROM users WHERE phone LIKE '+84999%');
DELETE FROM users
 WHERE phone LIKE '+84999%';

\echo '== pg_trgm availability and installed location =='
SELECT name, default_version, installed_version
  FROM pg_available_extensions
 WHERE name IN ('pg_trgm', 'unaccent')
 ORDER BY name;
SELECT e.extname, n.nspname AS installed_schema
  FROM pg_extension e
  JOIN pg_namespace n ON n.oid = e.extnamespace
 WHERE e.extname IN ('pg_trgm', 'unaccent')
 ORDER BY e.extname;

INSERT INTO users (id, phone, password_hash, display_name)
SELECT md5('nexachat-qa-20261105-user-' || n)::uuid,
       '+84999' || lpad(n::text, 9, '0'),
       repeat('0', 60),
       CASE WHEN n = 1 THEN 'QA scale sender' ELSE 'QA scale peer ' || n END
  FROM generate_series(1, 51) AS n;

INSERT INTO conversations (id, type, direct_key, created_by)
SELECT md5('nexachat-qa-20261105-conversation-' || n)::uuid,
       'DIRECT',
       'qa-perf-20261105-' || lpad(n::text, 4, '0'),
       md5('nexachat-qa-20261105-user-1')::uuid
  FROM generate_series(1, 50) AS n;

INSERT INTO conversation_members (conversation_id, user_id, role)
SELECT md5('nexachat-qa-20261105-conversation-' || n)::uuid,
       md5('nexachat-qa-20261105-user-1')::uuid,
       'MEMBER'
  FROM generate_series(1, 50) AS n
UNION ALL
SELECT md5('nexachat-qa-20261105-conversation-' || n)::uuid,
       md5('nexachat-qa-20261105-user-' || (n + 1))::uuid,
       'MEMBER'
  FROM generate_series(1, 50) AS n;

INSERT INTO messages
       (id, conversation_id, sender_id, client_message_id, type, content, created_at)
SELECT md5('nexachat-qa-20261105-message-' || g)::uuid,
       md5('nexachat-qa-20261105-conversation-' || (((g - 1) % 50) + 1))::uuid,
       CASE WHEN g % 2 = 0
            THEN md5('nexachat-qa-20261105-user-1')::uuid
            ELSE md5('nexachat-qa-20261105-user-' || ((((g - 1) % 50) + 1) + 1))::uuid
       END,
       md5('nexachat-qa-20261105-client-' || g)::uuid,
       'TEXT',
       CASE WHEN g % 997 = 0
            THEN 'qa-search-rare-marker-' || g || ' ordinary payload'
            ELSE 'qa-scale ordinary message ' || g
       END,
       now() - (200000 - g) * interval '1 millisecond'
  FROM generate_series(1, 200000) AS g;

UPDATE conversations c
   SET last_message_id = latest.id,
       last_message_seq = latest.seq
  FROM (
        SELECT DISTINCT ON (conversation_id) conversation_id, id, seq
          FROM messages
         ORDER BY conversation_id, seq DESC
       ) AS latest
 WHERE c.id = latest.conversation_id
   AND c.direct_key LIKE 'qa-perf-20261105-%';

UPDATE conversation_members m
   SET last_read_seq = latest.last_seq - 10
  FROM (
        SELECT conversation_id, max(seq) AS last_seq
          FROM messages
         GROUP BY conversation_id
       ) AS latest
 WHERE m.conversation_id = latest.conversation_id
   AND m.user_id = md5('nexachat-qa-20261105-user-1')::uuid;

ANALYZE messages;
ANALYZE conversations;
ANALYZE conversation_members;

\echo '== 200k seed confirmation =='
SELECT count(*) AS messages,
       count(DISTINCT conversation_id) AS conversations,
       min(seq) AS min_seq,
       max(seq) AS max_seq
  FROM messages
 WHERE conversation_id IN (
       SELECT id FROM conversations WHERE direct_key LIKE 'qa-perf-20261105-%');

\echo '== history paging: 31 newest rows before the latest 30 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.conversation_id, m.seq, u.display_name, m.type, m.content,
       m.client_message_id, reply.id AS reply_to_id, m.created_at, m.edited_at, m.deleted_at
  FROM messages m
  JOIN users u ON u.id = m.sender_id
  LEFT JOIN messages reply ON reply.id = m.reply_to_id
 WHERE m.conversation_id = md5('nexachat-qa-20261105-conversation-50')::uuid
   AND m.seq < (
       SELECT max(seq) - 30
         FROM messages
        WHERE conversation_id = md5('nexachat-qa-20261105-conversation-50')::uuid)
 ORDER BY m.seq DESC
 LIMIT 31;

\echo '== search before functional trigram index =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.conversation_id, m.seq, u.display_name, m.type, m.content,
       m.client_message_id, reply.id AS reply_to_id, m.created_at, m.edited_at, m.deleted_at
  FROM messages m
  JOIN users u ON u.id = m.sender_id
  LEFT JOIN messages reply ON reply.id = m.reply_to_id
 WHERE m.conversation_id = md5('nexachat-qa-20261105-conversation-50')::uuid
   AND m.type = 'TEXT'
   AND m.deleted_at IS NULL
   AND m.seq < (SELECT max(seq) + 1 FROM messages
                 WHERE conversation_id = md5('nexachat-qa-20261105-conversation-50')::uuid)
   AND public.unaccent(m.content)
       ILIKE public.unaccent('%qa-search-rare-marker%') ESCAPE chr(92)
 ORDER BY m.seq DESC
 LIMIT 21;

\echo '== proposed V5 search expression and index (temporary benchmark only) =='
CREATE FUNCTION nexachat_qa_20261105.qa_immutable_unaccent(value text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT public.unaccent('public.unaccent'::regdictionary, value)
$$;

CREATE INDEX idx_qa_perf_messages_search_trgm
    ON messages USING gin (qa_immutable_unaccent(content) public.gin_trgm_ops)
    WHERE type = 'TEXT' AND deleted_at IS NULL;
ANALYZE messages;

\echo '== search after functional trigram index =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.conversation_id, m.seq, u.display_name, m.type, m.content,
       m.client_message_id, reply.id AS reply_to_id, m.created_at, m.edited_at, m.deleted_at
  FROM messages m
  JOIN users u ON u.id = m.sender_id
  LEFT JOIN messages reply ON reply.id = m.reply_to_id
 WHERE m.conversation_id = md5('nexachat-qa-20261105-conversation-50')::uuid
   AND m.type = 'TEXT'
   AND m.deleted_at IS NULL
   AND m.seq < (SELECT max(seq) + 1 FROM messages
                 WHERE conversation_id = md5('nexachat-qa-20261105-conversation-50')::uuid)
   AND qa_immutable_unaccent(m.content)
       ILIKE qa_immutable_unaccent('%qa-search-rare-marker%') ESCAPE chr(92)
 ORDER BY m.seq DESC
 LIMIT 21;

\echo '== inbox query for benchmark sender =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id, c.type, c.title, c.avatar_url, c.last_message_id,
       c.last_message_seq, c.created_at, m.last_read_seq
  FROM conversation_members m
  JOIN conversations c ON c.id = m.conversation_id
 WHERE m.user_id = md5('nexachat-qa-20261105-user-1')::uuid
   AND c.direct_key LIKE 'qa-perf-20261105-%'
 ORDER BY coalesce(c.last_message_seq, 0) DESC, c.created_at DESC
 LIMIT 31;

\echo '== unread counts query for benchmark sender =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.conversation_id, count(msg)
  FROM conversation_members m, messages msg
 WHERE m.user_id = md5('nexachat-qa-20261105-user-1')::uuid
   AND m.conversation_id IN (
       SELECT id FROM conversations WHERE direct_key LIKE 'qa-perf-20261105-%')
   AND msg.conversation_id = m.conversation_id
   AND msg.seq > m.last_read_seq
 GROUP BY m.conversation_id;

\echo '== direct-peer presence lookup =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT own.conversation_id, peer.user_id, peer_user.show_presence, peer_user.last_seen_at
  FROM conversation_members own
  JOIN conversations c ON c.id = own.conversation_id
  JOIN conversation_members peer ON peer.conversation_id = own.conversation_id
  JOIN users peer_user ON peer_user.id = peer.user_id
 WHERE own.user_id = md5('nexachat-qa-20261105-user-1')::uuid
   AND c.type = 'DIRECT'
   AND own.conversation_id IN (
       SELECT id FROM conversations WHERE direct_key LIKE 'qa-perf-20261105-%')
   AND peer.user_id <> own.user_id;

DROP INDEX nexachat_qa_20261105.idx_qa_perf_messages_search_trgm;
DROP FUNCTION nexachat_qa_20261105.qa_immutable_unaccent(text);

\echo '== cleanup synthetic benchmark data =='
-- The reply_to_id FK is intentionally not indexed in the production schema.
-- Add a transaction-scoped helper so the 200k-row cascade does not perform a
-- full messages-table scan for every deleted message.
CREATE INDEX idx_qa_perf_cleanup_reply_ref ON messages (reply_to_id);
DELETE FROM conversations
 WHERE direct_key LIKE 'qa-perf-20261105-%';
DROP INDEX idx_qa_perf_cleanup_reply_ref;
DELETE FROM message_attachments
 WHERE owner_id IN (SELECT id FROM users WHERE phone LIKE '+84999%');
DELETE FROM users
 WHERE phone LIKE '+84999%';
ANALYZE messages;
ANALYZE conversations;
ANALYZE conversation_members;

COMMIT;

\echo '== cleanup verified =='
SELECT count(*) AS remaining_seed_users
  FROM users
 WHERE phone LIKE '+84999%';

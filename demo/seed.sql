-- ============================================================================
-- NexaChat demo seed — DEMO DATA ONLY. Never use these credentials elsewhere.
--
-- Usage (after `docker compose up -d db` and Flyway migrations):
--   docker exec -i nexachat-db-1 psql -U nexachat -d nexachat < demo/seed.sql
-- or from the host:
--   psql -h localhost -U nexachat -d nexachat -f demo/seed.sql
--
-- Accounts (all password: password123):
--   +84900000001 An   +84900000002 Binh
--   +84900000003 Chi  +84900000004 Dung
-- password_hash is bcrypt cost 10 and verifies against "password123".
--
-- Re-runnable: every INSERT uses ON CONFLICT DO NOTHING.
--
-- IT cleanup warning: the integration-test cleanup deletes users matching
-- '+8490%' EXCEPT '+849000%'. These seed phones start with '+8490000', so
-- they match the exclusion and survive test runs.
--
-- If an older demo seed (password demo@1234) was applied before, these rows
-- are skipped and keep the old hash; reset them first:
--   DELETE FROM users WHERE phone LIKE '+8490000%';
-- (cascades to contacts/conversations/messages via FK).
--
-- Schema: V1__init.sql. DIRECT conversations require direct_key, whose format
-- is lowerUuid:higherUuid (ConversationService.directKey). messages.seq is a
-- global BIGSERIAL; per-conversation ordering still holds because seq only
-- increases.
-- ============================================================================

BEGIN;

-- 1) Users -------------------------------------------------------------------
INSERT INTO users (id, phone, password_hash, display_name) VALUES
  ('11111111-1111-4111-8111-111111111111', '+84900000001',
   '$2b$10$6CIW2M4Jh/cuaQT0tYKZeOFU/3tKnsl0.J2VRtPK2rPD14efb6j6e', 'An'),
  ('22222222-2222-4222-8222-222222222222', '+84900000002',
   '$2b$10$6CIW2M4Jh/cuaQT0tYKZeOFU/3tKnsl0.J2VRtPK2rPD14efb6j6e', 'Binh'),
  ('33333333-3333-4333-8333-333333333333', '+84900000003',
   '$2b$10$6CIW2M4Jh/cuaQT0tYKZeOFU/3tKnsl0.J2VRtPK2rPD14efb6j6e', 'Chi'),
  ('44444444-4444-4444-8444-444444444444', '+84900000004',
   '$2b$10$6CIW2M4Jh/cuaQT0tYKZeOFU/3tKnsl0.J2VRtPK2rPD14efb6j6e', 'Dung')
ON CONFLICT DO NOTHING;
-- password: password123 (bcrypt cost 10)

-- 2) Contacts ----------------------------------------------------------------
INSERT INTO contacts (owner_id, contact_user_id) VALUES
  ('11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222'), -- An -> Binh
  ('11111111-1111-4111-8111-111111111111', '33333333-3333-4333-8333-333333333333'), -- An -> Chi
  ('22222222-2222-4222-8222-222222222222', '11111111-1111-4111-8111-111111111111'), -- Binh -> An
  ('22222222-2222-4222-8222-222222222222', '44444444-4444-4444-8444-444444444444')  -- Binh -> Dung
ON CONFLICT DO NOTHING;

-- 3) Conversations -----------------------------------------------------------
INSERT INTO conversations
  (id, type, direct_key, title, created_by, last_message_id, last_message_seq, created_at)
VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'DIRECT',
   '11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222',
   NULL, '11111111-1111-4111-8111-111111111111',
   '00000000-0000-4000-8000-000000000006', 6, now() - interval '1 hour'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'GROUP',
   NULL, 'NexaChat Team', '11111111-1111-4111-8111-111111111111',
   '00000000-0000-4000-8000-000000000010', 10, now() - interval '40 minutes')
ON CONFLICT DO NOTHING;

-- 4) Conversation members ----------------------------------------------------
INSERT INTO conversation_members
  (conversation_id, user_id, role, joined_at, last_read_seq)
VALUES
  -- DIRECT: An read all 6, Binh read only 4 -> unread badge for Binh
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111111', 'MEMBER', now() - interval '2 hours', 6),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '22222222-2222-4222-8222-222222222222', 'MEMBER', now() - interval '2 hours', 4),
  -- GROUP: An ADMIN read all 10, Binh 8, Chi 10, Dung 0
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '11111111-1111-4111-8111-111111111111', 'ADMIN',  now() - interval '45 minutes', 10),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '22222222-2222-4222-8222-222222222222', 'MEMBER', now() - interval '45 minutes', 8),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '33333333-3333-4333-8333-333333333333', 'MEMBER', now() - interval '45 minutes', 10),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '44444444-4444-4444-8444-444444444444', 'MEMBER', now() - interval '45 minutes', 0)
ON CONFLICT DO NOTHING;

-- 5) Messages ----------------------------------------------------------------
-- Explicit seq values keep the demo reproducible: direct 1..6, group 7..10.
INSERT INTO messages
  (id, seq, conversation_id, sender_id, client_message_id, type, content, reply_to_id, created_at)
VALUES
  -- DIRECT: An <-> Binh, alternating senders
  ('00000000-0000-4000-8000-000000000001', 1, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
   '11111111-1111-4111-8111-111111111111', 'f0000000-0000-4000-8000-000000000001',
   'TEXT', 'Bình ơi, họp lúc 3 giờ chiều đúng không?', NULL, now() - interval '55 minutes'),
  ('00000000-0000-4000-8000-000000000002', 2, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
   '22222222-2222-4222-8222-222222222222', 'f0000000-0000-4000-8000-000000000002',
   'TEXT', 'Đúng rồi, phòng 2A nhé.', NULL, now() - interval '45 minutes'),
  ('00000000-0000-4000-8000-000000000003', 3, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
   '11111111-1111-4111-8111-111111111111', 'f0000000-0000-4000-8000-000000000003',
   'TEXT', 'Ok em. À Công ty vừa gửi lại spec mới, em forward sang đây.', NULL, now() - interval '35 minutes'),
  ('00000000-0000-4000-8000-000000000004', 4, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
   '22222222-2222-4222-8222-222222222222', 'f0000000-0000-4000-8000-000000000004',
   'TEXT', 'Nhận rồi, cảm ơn bạn.', NULL, now() - interval '25 minutes'),
  ('00000000-0000-4000-8000-000000000005', 5, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
   '11111111-1111-4111-8111-111111111111', 'f0000000-0000-4000-8000-000000000005',
   'TEXT', 'Không có gì. Cuối tuần này đi cà phê không?', NULL, now() - interval '15 minutes'),
  ('00000000-0000-4000-8000-000000000006', 6, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
   '22222222-2222-4222-8222-222222222222', 'f0000000-0000-4000-8000-000000000006',
   'TEXT', 'Được, bao mình.', NULL, now() - interval '5 minutes'),
  -- GROUP 'NexaChat Team': An -> Chi -> Dung -> Binh
  ('00000000-0000-4000-8000-000000000007', 7, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '11111111-1111-4111-8111-111111111111', 'f0000000-0000-4000-8000-000000000007',
   'TEXT', 'Chào mọi người, bản phát hành cuối tuần này đã sẵn sàng nhé.', NULL, now() - interval '38 minutes'),
  ('00000000-0000-4000-8000-000000000008', 8, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '33333333-3333-4333-8333-333333333333', 'f0000000-0000-4000-8000-000000000008',
   'TEXT', 'Em làm xong phần API rồi, đang viết slide.', NULL, now() - interval '30 minutes'),
  ('00000000-0000-4000-8000-000000000009', 9, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '44444444-4444-4444-8444-444444444444', 'f0000000-0000-4000-8000-000000000009',
   'TEXT', 'Tui làm xong UI, còn 2 case test nữa.', NULL, now() - interval '20 minutes'),
  ('00000000-0000-4000-8000-000000000010', 10, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   '22222222-2222-4222-8222-222222222222', 'f0000000-0000-4000-8000-000000000010',
   'TEXT', 'Ok, tối nay 8 giờ mình tổng hợp lại nhé.', NULL, now() - interval '8 minutes')
ON CONFLICT DO NOTHING;

-- Keep the BIGSERIAL sequence ahead of the explicit seq values so later
-- app-inserted messages do not collide with the seed rows.
SELECT setval(
  pg_get_serial_sequence('messages', 'seq'),
  GREATEST(COALESCE((SELECT MAX(seq) FROM messages), 0), 1),
  EXISTS (SELECT 1 FROM messages)
);

COMMIT;

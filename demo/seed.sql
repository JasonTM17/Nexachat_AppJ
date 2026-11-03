-- ============================================================================
-- NexaChat demo seed — DEMO DATA ONLY. Never use these credentials elsewhere.
--
-- Creates 3 demo users for the LAN demo (docs/demo-runbook.md):
--   +84900000001 Minh · +84900000002 Lan · +84900000003 Huy
--   password: demo@1234 (demo only)
--
-- How to run (after `docker compose up -d db` and Flyway migrations):
--   psql -h localhost -U nexachat -d nexachat -f demo/seed.sql
-- or inside the container:
--   docker compose exec -T db psql -U nexachat -d nexachat < demo/seed.sql
--
-- Safe to re-run: ON CONFLICT (phone) DO NOTHING keeps it idempotent.
-- Schema reference: users(phone E.164, password_hash CHAR(60) bcrypt,
-- display_name) — master design §9.2.
-- ============================================================================

BEGIN;

INSERT INTO users (phone, password_hash, display_name) VALUES
  ('+84900000001', '$2b$10$60XEFaht65nk/IuC2nIJUevMjHT87q9fElIRwJdvASS6i/p.9mmSG', 'Minh'),
  ('+84900000002', '$2b$10$60XEFaht65nk/IuC2nIJUevMjHT87q9fElIRwJdvASS6i/p.9mmSG', 'Lan'),
  ('+84900000003', '$2b$10$60XEFaht65nk/IuC2nIJUevMjHT87q9fElIRwJdvASS6i/p.9mmSG', 'Huy')
ON CONFLICT (phone) DO NOTHING;
-- password: demo@1234 (demo only)

COMMIT;

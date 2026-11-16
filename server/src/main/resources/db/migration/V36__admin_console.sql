-- V36: admin console — account roles, suspension, immutable audit log and the
-- sensitive-message alert pipeline (non-E2EE messages only).
--
-- users.role carries the global account role (USER/MODERATOR/ADMIN);
-- suspended_* columns record an administrative suspension. The JWT hot path
-- reads role + suspended_at through the existing findAuthValidityById
-- projection so suspension is enforced on REST, STOMP CONNECT and every
-- subsequent session action without touching the token contract.

ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('USER', 'MODERATOR', 'ADMIN'));
ALTER TABLE users ADD COLUMN suspended_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN suspended_reason TEXT;
ALTER TABLE users ADD COLUMN suspended_by UUID;

-- Staff accounts and suspended accounts are rare; partial indexes keep the
-- admin list/count queries off the full user table.
CREATE INDEX users_role_idx ON users (role) WHERE role <> 'USER' AND deleted_at IS NULL;
CREATE INDEX users_suspended_idx ON users (suspended_at DESC)
    WHERE suspended_at IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX users_created_idx ON users (created_at DESC);

-- ---------------------------------------------------------------- audit log
-- Append-only: every admin mutation and every message-content reveal lands one
-- row. The trigger below rejects UPDATE and DELETE outright — there is no
-- application path (or operator path short of dropping the trigger) that can
-- rewrite history.
CREATE TABLE admin_audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID        NOT NULL,
    actor_role  VARCHAR(16) NOT NULL,
    action      VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id   VARCHAR(64),
    reason      TEXT,
    detail      JSONB,
    ip          VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX admin_audit_log_created_idx ON admin_audit_log (created_at DESC);
CREATE INDEX admin_audit_log_actor_idx ON admin_audit_log (actor_id, created_at DESC);
CREATE INDEX admin_audit_log_target_idx ON admin_audit_log (target_type, target_id);
CREATE INDEX admin_audit_log_action_idx ON admin_audit_log (action, created_at DESC);

CREATE OR REPLACE FUNCTION admin_audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER admin_audit_log_no_update
    BEFORE UPDATE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION admin_audit_log_immutable();
CREATE TRIGGER admin_audit_log_no_delete
    BEFORE DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION admin_audit_log_immutable();

-- ------------------------------------------------------------- alert rules
-- pattern_type: KEYWORD (normalized substring), REGEX (bounded java regex,
-- validated for ReDoS shape at write time), CARD (Luhn + format detector —
-- pattern column is inert and only labels the detector).
CREATE TABLE alert_rules (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(80)  NOT NULL,
    category     VARCHAR(32)  NOT NULL CHECK (category IN
        ('SELF_HARM', 'THREAT_VIOLENCE', 'SCAM_PHISHING', 'CREDENTIAL_SHARING',
         'ID_NUMBER', 'MINOR_SOLICITATION', 'DOXXING', 'CUSTOM')),
    severity     VARCHAR(8)   NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    pattern_type VARCHAR(8)   NOT NULL CHECK (pattern_type IN ('KEYWORD', 'REGEX', 'CARD')),
    pattern      TEXT         NOT NULL,
    locale       VARCHAR(8)   NOT NULL DEFAULT 'any',
    built_in     BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    description  VARCHAR(400),
    created_by   UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX alert_rules_enabled_idx ON alert_rules (enabled) WHERE enabled;

-- --------------------------------------------------------------- alerts
-- One alert per (message, category): several rules of one category on the same
-- message merge into a single row, and matched_rule_ids keeps the union.
-- severity stores the highest matched severity. Only a redacted snippet is
-- persisted — never full content; snippet is tombstoned when the message is
-- deleted or the sender opts out of AI processing afterwards.
CREATE TABLE message_alerts (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id         UUID        NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    conversation_id    UUID        NOT NULL,
    sender_id          UUID        NOT NULL,
    message_seq        BIGINT      NOT NULL,
    category           VARCHAR(32) NOT NULL,
    severity           VARCHAR(8)  NOT NULL,
    matched_rule_ids   UUID[]      NOT NULL,
    snippet            TEXT,
    snippet_tombstoned BOOLEAN     NOT NULL DEFAULT FALSE,
    status             VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN
        ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED')),
    assignee_id        UUID,
    assigned_at        TIMESTAMPTZ,
    resolved_by        UUID,
    resolved_at        TIMESTAMPTZ,
    escalated          BOOLEAN     NOT NULL DEFAULT FALSE,
    dry_run            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, category)
);
CREATE INDEX message_alerts_status_idx ON message_alerts (status, created_at DESC);
CREATE INDEX message_alerts_severity_idx ON message_alerts (severity, created_at DESC);
CREATE INDEX message_alerts_conversation_idx ON message_alerts (conversation_id, created_at DESC);
CREATE INDEX message_alerts_sender_idx ON message_alerts (sender_id, created_at DESC);
CREATE INDEX message_alerts_created_idx ON message_alerts (created_at DESC);

CREATE TABLE alert_notes (
    id         BIGSERIAL PRIMARY KEY,
    alert_id   UUID        NOT NULL REFERENCES message_alerts (id) ON DELETE CASCADE,
    author_id  UUID        NOT NULL,
    body       VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX alert_notes_alert_idx ON alert_notes (alert_id, id);

-- User-facing moderation notices (warn-user action output). The user reads
-- these through their own authenticated endpoint; admins create them from an
-- alert or the user drawer.
CREATE TABLE user_notices (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id),
    alert_id   UUID        REFERENCES message_alerts (id) ON DELETE SET NULL,
    kind       VARCHAR(16) NOT NULL CHECK (kind IN ('WARNING')),
    body       VARCHAR(1000) NOT NULL,
    created_by UUID,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX user_notices_user_idx ON user_notices (user_id, created_at DESC);

-- ------------------------------------------------------- built-in rule set
-- Fixed ids keep tests, docs and dry-run diffs stable across environments.
-- English + Vietnamese coverage for every required category. Keywords match
-- against diacritic-folded, case-folded text, so Vietnamese rows list the
-- accented surface forms that fold to the same key.
INSERT INTO alert_rules (id, name, category, severity, pattern_type, pattern, locale, built_in, description) VALUES
-- self-harm
('a1000000-0000-4000-8000-000000000001','self-harm direct intent','SELF_HARM','CRITICAL','KEYWORD','kill myself','en',TRUE,'Explicit self-harm intent'),
('a1000000-0000-4000-8000-000000000002','self-harm ideation','SELF_HARM','HIGH','KEYWORD','want to die','en',TRUE,'Suicidal ideation phrase'),
('a1000000-0000-4000-8000-000000000003','self-harm suicide','SELF_HARM','HIGH','KEYWORD','suicide','en',TRUE,'Suicide mention'),
('a1000000-0000-4000-8000-000000000004','self-harm end my life','SELF_HARM','CRITICAL','KEYWORD','end my life','en',TRUE,'Explicit self-harm intent'),
('a1000000-0000-4000-8000-000000000005','tu tu / tu sat','SELF_HARM','CRITICAL','KEYWORD','tự tử','vi',TRUE,'Vietnamese for suicide'),
('a1000000-0000-4000-8000-000000000006','tu sat variant','SELF_HARM','CRITICAL','KEYWORD','tự sát','vi',TRUE,'Vietnamese for suicide'),
('a1000000-0000-4000-8000-000000000007','muon chet','SELF_HARM','HIGH','KEYWORD','muốn chết','vi',TRUE,'Vietnamese ideation'),
('a1000000-0000-4000-8000-000000000008','song chi de lam gi','SELF_HARM','HIGH','KEYWORD','sống chi để làm gì','vi',TRUE,'Vietnamese ideation'),
-- threats / violence
('a1000000-0000-4000-8000-000000000010','kill you','THREAT_VIOLENCE','HIGH','KEYWORD','kill you','en',TRUE,'Direct threat'),
('a1000000-0000-4000-8000-000000000011','gonna kill','THREAT_VIOLENCE','HIGH','KEYWORD','gonna kill','en',TRUE,'Direct threat'),
('a1000000-0000-4000-8000-000000000012','hurt you','THREAT_VIOLENCE','MEDIUM','KEYWORD','hurt you','en',TRUE,'Threat of harm'),
('a1000000-0000-4000-8000-000000000013','tao giet may','THREAT_VIOLENCE','HIGH','KEYWORD','giết mày','vi',TRUE,'Vietnamese death threat'),
('a1000000-0000-4000-8000-000000000014','danh chet','THREAT_VIOLENCE','HIGH','KEYWORD','đánh chết','vi',TRUE,'Vietnamese threat'),
('a1000000-0000-4000-8000-000000000015','sat hai','THREAT_VIOLENCE','HIGH','KEYWORD','sát hại','vi',TRUE,'Vietnamese threat'),
-- scams / phishing
('a1000000-0000-4000-8000-000000000020','wire transfer','SCAM_PHISHING','HIGH','KEYWORD','wire transfer','en',TRUE,'Classic fraud vector'),
('a1000000-0000-4000-8000-000000000021','lottery winner','SCAM_PHISHING','HIGH','KEYWORD','lottery winner','en',TRUE,'Lottery scam'),
('a1000000-0000-4000-8000-000000000022','verify your account','SCAM_PHISHING','MEDIUM','KEYWORD','verify your account','en',TRUE,'Phishing lure'),
('a1000000-0000-4000-8000-000000000023','claim your prize','SCAM_PHISHING','HIGH','KEYWORD','claim your prize','en',TRUE,'Prize scam'),
('a1000000-0000-4000-8000-000000000024','trung thuong','SCAM_PHISHING','HIGH','KEYWORD','trúng thưởng','vi',TRUE,'Vietnamese lottery scam'),
('a1000000-0000-4000-8000-000000000025','giai thuong','SCAM_PHISHING','MEDIUM','KEYWORD','giải thưởng','vi',TRUE,'Vietnamese prize lure'),
('a1000000-0000-4000-8000-000000000026','dau tu lai cao','SCAM_PHISHING','HIGH','KEYWORD','đầu tư lãi cao','vi',TRUE,'Vietnamese investment scam'),
('a1000000-0000-4000-8000-000000000027','chuyen khoan xac nhan','SCAM_PHISHING','HIGH','KEYWORD','chuyển khoản xác nhận','vi',TRUE,'Vietnamese transfer phishing'),
-- credential / OTP sharing
('a1000000-0000-4000-8000-000000000030','send otp','CREDENTIAL_SHARING','HIGH','KEYWORD','send me the code','en',TRUE,'OTP harvesting'),
('a1000000-0000-4000-8000-000000000031','verification code','CREDENTIAL_SHARING','MEDIUM','KEYWORD','verification code','en',TRUE,'OTP reference'),
('a1000000-0000-4000-8000-000000000032','your password','CREDENTIAL_SHARING','MEDIUM','KEYWORD','your password','en',TRUE,'Credential harvesting'),
('a1000000-0000-4000-8000-000000000033','ma otp','CREDENTIAL_SHARING','HIGH','KEYWORD','mã otp','vi',TRUE,'Vietnamese OTP reference'),
('a1000000-0000-4000-8000-000000000034','ma xac minh','CREDENTIAL_SHARING','HIGH','KEYWORD','mã xác minh','vi',TRUE,'Vietnamese verification code'),
('a1000000-0000-4000-8000-000000000035','gui ma cho','CREDENTIAL_SHARING','HIGH','KEYWORD','gửi mã cho','vi',TRUE,'Vietnamese code harvesting'),
('a1000000-0000-4000-8000-000000000036','mat khau','CREDENTIAL_SHARING','MEDIUM','KEYWORD','mật khẩu','vi',TRUE,'Vietnamese password reference'),
-- card / ID numbers
('a1000000-0000-4000-8000-000000000040','payment card number','ID_NUMBER','HIGH','CARD','bank card','any',TRUE,'Luhn-valid card number in message'),
('a1000000-0000-4000-8000-000000000041','vn national id','ID_NUMBER','MEDIUM','REGEX','\b\d{9}\b|\b\d{12}\b','vi',TRUE,'9/12-digit VN ID pattern'),
('a1000000-0000-4000-8000-000000000042','us ssn shape','ID_NUMBER','MEDIUM','REGEX','\b\d{3}-\d{2}-\d{4}\b','en',TRUE,'SSN-shaped number'),
-- adult-content solicitation of minors
('a1000000-0000-4000-8000-000000000050','send nudes','MINOR_SOLICITATION','CRITICAL','KEYWORD','send nudes','en',TRUE,'Explicit solicitation'),
('a1000000-0000-4000-8000-000000000051','under 18 pics','MINOR_SOLICITATION','CRITICAL','KEYWORD','under 18','en',TRUE,'Minor-age solicitation context'),
('a1000000-0000-4000-8000-000000000052','anh nong em','MINOR_SOLICITATION','CRITICAL','KEYWORD','ảnh nóng','vi',TRUE,'Vietnamese explicit-image solicitation'),
('a1000000-0000-4000-8000-000000000053','em be anh','MINOR_SOLICITATION','CRITICAL','KEYWORD','anh khoe cua ay','vi',TRUE,'Vietnamese CSAM-risk phrase'),
-- doxxing
('a1000000-0000-4000-8000-000000000060','home address is','DOXXING','MEDIUM','KEYWORD','home address is','en',TRUE,'Address disclosure'),
('a1000000-0000-4000-8000-000000000061','doxx','DOXXING','HIGH','KEYWORD','doxx','en',TRUE,'Doxxing term'),
('a1000000-0000-4000-8000-000000000062','leak dia chi','DOXXING','HIGH','KEYWORD','địa chỉ nhà của','vi',TRUE,'Vietnamese home-address disclosure'),
('a1000000-0000-4000-8000-000000000063','so dien thoai nha','DOXXING','MEDIUM','KEYWORD','số điện thoại nhà','vi',TRUE,'Vietnamese personal-phone disclosure')
ON CONFLICT (id) DO NOTHING;

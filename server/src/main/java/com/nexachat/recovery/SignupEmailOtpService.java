package com.nexachat.recovery;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One-time codes that prove a person owns an email address before any account
 * exists. The code is bound to the normalized address, expires after ten
 * minutes, accepts five wrong guesses and is consumed by one successful use.
 */
@Service
public class SignupEmailOtpService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration RETENTION = Duration.ofDays(1);

    private final JdbcTemplate jdbcTemplate;
    private final OtpCodeHasher codeHasher;
    private final SignupOtpAttemptRecorder attemptRecorder;
    private final Clock clock;

    public SignupEmailOtpService(JdbcTemplate jdbcTemplate,
                                 OtpCodeHasher codeHasher,
                                 SignupOtpAttemptRecorder attemptRecorder,
                                 Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeHasher = codeHasher;
        this.attemptRecorder = attemptRecorder;
        this.clock = clock;
    }

    /** Fails with a retry hint while the previous code for this address is still fresh. */
    public void requireCooldownElapsed(String email) {
        Instant now = clock.instant();
        Timestamp latest = jdbcTemplate.queryForObject(
                "SELECT MAX(created_at) FROM signup_email_otps WHERE email = ?", Timestamp.class, email);
        if (latest != null && latest.toInstant().plus(SEND_COOLDOWN).isAfter(now)) {
            long retryAfter = Math.max(1L,
                    Duration.between(now, latest.toInstant().plus(SEND_COOLDOWN)).toSeconds());
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", retryAfter));
        }
    }

    /** Replaces any active code for the address with a new one. */
    @Transactional
    public OtpMail issue(String email) {
        lock(email);
        requireCooldownElapsed(email);
        Instant now = clock.instant();
        jdbcTemplate.update("DELETE FROM signup_email_otps WHERE created_at < ?",
                Timestamp.from(now.minus(RETENTION)));
        jdbcTemplate.update("UPDATE signup_email_otps SET consumed_at = ?"
                + " WHERE email = ? AND consumed_at IS NULL", Timestamp.from(now), email);
        String code = codeHasher.newCode();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO signup_email_otps"
                        + " (id, email, code_hash, expires_at, attempts, consumed_at, created_at)"
                        + " VALUES (?, ?, ?, ?, 0, NULL, ?)",
                id, email, hash(email, code), Timestamp.from(now.plus(CODE_TTL)), Timestamp.from(now));
        return new OtpMail(id, email, OtpPurpose.REGISTER_EMAIL, code);
    }

    /**
     * Consumes the active code for the address when {@code code} matches.
     * A wrong guess is counted in its own transaction so it survives a rollback
     * of the caller.
     */
    @Transactional
    public boolean consume(String email, String code) {
        lock(email);
        Instant now = clock.instant();
        var rows = jdbcTemplate.query(
                "SELECT id, code_hash, expires_at, attempts FROM signup_email_otps"
                        + " WHERE email = ? AND consumed_at IS NULL ORDER BY created_at DESC LIMIT 1",
                (rs, n) -> new Active(rs.getObject("id", UUID.class), rs.getString("code_hash").trim(),
                        rs.getTimestamp("expires_at").toInstant(), rs.getInt("attempts")),
                email);
        if (rows.isEmpty()) {
            codeHasher.dummyCompare(OtpPurpose.REGISTER_EMAIL, code);
            return false;
        }
        Active active = rows.get(0);
        if (!active.expiresAt().isAfter(now) || active.attempts() >= 5) {
            codeHasher.dummyCompare(OtpPurpose.REGISTER_EMAIL, code);
            return false;
        }
        String supplied = hash(email, code == null ? "" : code);
        if (!java.security.MessageDigest.isEqual(
                active.codeHash().getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII))) {
            attemptRecorder.recordWrongAttempt(active.id(), now);
            return false;
        }
        return jdbcTemplate.update("UPDATE signup_email_otps SET consumed_at = ?"
                        + " WHERE id = ? AND consumed_at IS NULL AND expires_at > ? AND attempts < 5",
                Timestamp.from(now), active.id(), Timestamp.from(now)) == 1;
    }

    /** Withdraws a code whose mail could not be delivered. */
    @Transactional
    public void cancel(UUID otpId) {
        jdbcTemplate.update("UPDATE signup_email_otps SET consumed_at = ?"
                + " WHERE id = ? AND consumed_at IS NULL", Timestamp.from(clock.instant()), otpId);
    }

    public void dummyCompare(String code) {
        codeHasher.dummyCompare(OtpPurpose.REGISTER_EMAIL, code);
    }

    private void lock(String email) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0)) IS NOT NULL",
                Boolean.class, "signup-email|" + email);
    }

    private String hash(String email, String code) {
        UUID scope = UUID.nameUUIDFromBytes(("signup-email|" + email).getBytes(StandardCharsets.UTF_8));
        return codeHasher.hash(scope, OtpPurpose.REGISTER_EMAIL, code);
    }

    private record Active(UUID id, String codeHash, Instant expiresAt, int attempts) {
    }
}

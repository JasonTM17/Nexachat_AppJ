package com.nexachat.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class EmailOtpRepository {

    private static final String COLUMNS = "id, user_id, purpose, email, code_hash, expires_at, attempts, consumed_at";
    private final JdbcTemplate jdbcTemplate;

    EmailOtpRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void lockVerifiedEmail(String email) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0)) IS NOT NULL",
                Boolean.class, email);
    }

    Instant latestCreatedAt(UUID userId, OtpPurpose purpose) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "SELECT MAX(created_at) FROM email_otps WHERE user_id = ? AND purpose = ?",
                Timestamp.class, userId, purpose.name());
        return timestamp == null ? null : timestamp.toInstant();
    }

    void invalidateActive(UUID userId, OtpPurpose purpose, Instant now) {
        jdbcTemplate.update("UPDATE email_otps SET consumed_at = ?"
                        + " WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL",
                Timestamp.from(now), userId, purpose.name());
    }

    void insert(UUID id, UUID userId, OtpPurpose purpose, String email,
                String codeHash, Instant expiresAt, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO email_otps"
                        + " (id, user_id, purpose, email, code_hash, expires_at, attempts, consumed_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, NULL, ?)",
                id, userId, purpose.name(), email, codeHash,
                Timestamp.from(expiresAt), Timestamp.from(createdAt));
    }

    Optional<EmailOtpRecord> findActive(UUID userId, OtpPurpose purpose, String email) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM email_otps"
                        + " WHERE user_id = ? AND purpose = ? AND email = ? AND consumed_at IS NULL"
                        + " ORDER BY created_at DESC LIMIT 1",
                (rs, rowNum) -> map(rs), userId, purpose.name(), email).stream().findFirst();
    }

    Optional<EmailOtpRecord> findActiveForUser(UUID userId, OtpPurpose purpose) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM email_otps"
                        + " WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL"
                        + " ORDER BY created_at DESC LIMIT 1",
                (rs, rowNum) -> map(rs), userId, purpose.name()).stream().findFirst();
    }

    boolean consume(UUID otpId, Instant now) {
        return jdbcTemplate.update("UPDATE email_otps SET consumed_at = ?"
                        + " WHERE id = ? AND consumed_at IS NULL AND expires_at > ? AND attempts < 5",
                Timestamp.from(now), otpId, Timestamp.from(now)) == 1;
    }

    void cancel(UUID otpId, Instant now) {
        jdbcTemplate.update("UPDATE email_otps SET consumed_at = ?"
                        + " WHERE id = ? AND consumed_at IS NULL",
                Timestamp.from(now), otpId);
    }

    private static EmailOtpRecord map(ResultSet rs) throws SQLException {
        Timestamp consumedAt = rs.getTimestamp("consumed_at");
        return new EmailOtpRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                OtpPurpose.valueOf(rs.getString("purpose")),
                rs.getString("email"),
                rs.getString("code_hash").trim(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("attempts"),
                consumedAt == null ? null : consumedAt.toInstant());
    }
}

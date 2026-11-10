package com.nexachat.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
class OtpAttemptRecorder {

    private final JdbcTemplate jdbcTemplate;

    OtpAttemptRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWrongAttempt(UUID otpId, Instant now) {
        jdbcTemplate.update("UPDATE email_otps"
                        + " SET attempts = attempts + 1,"
                        + " consumed_at = CASE WHEN attempts + 1 >= 5 THEN ? ELSE consumed_at END"
                        + " WHERE id = ? AND consumed_at IS NULL AND expires_at > ? AND attempts < 5",
                Timestamp.from(now), otpId, Timestamp.from(now));
    }
}

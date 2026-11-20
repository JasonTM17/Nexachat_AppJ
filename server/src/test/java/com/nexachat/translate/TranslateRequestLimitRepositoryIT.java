package com.nexachat.translate;

import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The multi-window rate limiter refunds the minute window when the daily
 * window refuses. The refund must leave the table in a state the schema
 * accepts: {@code request_count} is {@code CHECK > 0}, so a refund that
 * would zero the row has to remove it — otherwise the refusal surfaces as a
 * {@code DataIntegrityViolationException} (HTTP 500) instead of a clean
 * refusal, and every retry in that minute fails the same way.
 */
@SpringBootTest
@ActiveProfiles("it")
class TranslateRequestLimitRepositoryIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TranslateRequestLimitRepository repository;

    private User user;
    private final Instant windowStart = Instant.parse("2026-11-16T12:34:00Z")
            .truncatedTo(ChronoUnit.MINUTES);

    @BeforeEach
    void createFixture() {
        user = userRepository.save(User.create(
                ItPhoneRanges.nextPhone(TranslateRequestLimitRepositoryIT.class),
                "x".repeat(60), "Rate limit refund"));
    }

    @AfterEach
    void removeFixture() {
        if (user != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
        }
    }

    @Test
    void refundThatWouldZeroTheRowDeletesIt() {
        assertThat(repository.tryConsume(user.getId(), "MINUTE", windowStart, 30, 1)).isTrue();

        repository.refund(user.getId(), "MINUTE", windowStart, 1);

        assertThat(countFor("MINUTE")).isNull();
    }

    @Test
    void refundLeavesTheUnrefundedTokens() {
        assertThat(repository.tryConsume(user.getId(), "MINUTE", windowStart, 30, 3)).isTrue();

        repository.refund(user.getId(), "MINUTE", windowStart, 1);

        assertThat(countFor("MINUTE")).isEqualTo(2);
    }

    @Test
    void refundNeverBlocksTheNextConsume() {
        // The production failure mode: a daily-cap refusal refunded a fresh
        // minute row to zero, the CHECK rejected it, the transaction rolled
        // back — and the next attempt replayed the same 500.
        assertThat(repository.tryConsume(user.getId(), "MINUTE", windowStart, 30, 1)).isTrue();

        repository.refund(user.getId(), "MINUTE", windowStart, 1);

        assertThat(countFor("MINUTE")).isNull();
        assertThat(repository.tryConsume(user.getId(), "MINUTE", windowStart, 30, 1)).isTrue();
        assertThat(countFor("MINUTE")).isEqualTo(1);
    }

    private Integer countFor(String windowType) {
        return jdbcTemplate.query(
                """
                SELECT request_count FROM translate_request_limits
                WHERE user_id = ? AND window_type = ? AND window_start = ?
                """,
                statement -> {
                    statement.setObject(1, user.getId());
                    statement.setString(2, windowType);
                    statement.setObject(3,
                            OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));
                },
                (resultSet, rowNum) -> resultSet.getInt(1))
                .stream().findFirst().orElse(null);
    }
}

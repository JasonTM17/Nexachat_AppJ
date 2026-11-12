package com.nexachat.message;

import com.nexachat.testutil.ItPhoneRanges;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL-backed proof that a key reservation serializes concurrent mutations. */
@SpringBootTest
@ActiveProfiles("it")
class IdempotencyConcurrencyIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5432/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdempotencyService idempotencyService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void createUser() {
        String phone = ItPhoneRanges.nextPhone(IdempotencyConcurrencyIT.class);
        jdbcTemplate.update("INSERT INTO users (id, phone, password_hash, display_name) VALUES (?, ?, ?, ?)",
                userId, phone, "x".repeat(60), "Idempotency IT");
    }

    @AfterEach
    void deleteUser() {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void concurrentSameKeyExecutionsRunTheToggleActionOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch firstActionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstAction = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);
        CountDownLatch secondActionStarted = new CountDownLatch(1);
        AtomicInteger actionRuns = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> idempotencyService.execute(
                    userId, key, "POST /reactions/toggle", "message:emoji", () -> {
                        actionRuns.incrementAndGet();
                        firstActionStarted.countDown();
                        awaitLatch(releaseFirstAction);
                    }));
            assertThat(firstActionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> {
                secondCallStarted.countDown();
                idempotencyService.execute(userId, key, "POST /reactions/toggle", "message:emoji", () -> {
                    actionRuns.incrementAndGet();
                    secondActionStarted.countDown();
                });
            });
            assertThat(secondCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondActionStarted.await(5, TimeUnit.SECONDS))
                    .as("the second execution must wait on the first transaction's key reservation")
                    .isFalse();

            releaseFirstAction.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(actionRuns).hasValue(1);
        } finally {
            releaseFirstAction.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the test transaction release");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test transaction interrupted", e);
        }
    }
}

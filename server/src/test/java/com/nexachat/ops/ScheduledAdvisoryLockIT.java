package com.nexachat.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("it")
class ScheduledAdvisoryLockIT {

    private static final long TEST_LOCK = 2_530_099L;

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
    private ScheduledAdvisoryLock advisoryLock;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    @org.junit.jupiter.api.BeforeEach
    @AfterEach
    void clearHeldLocks() {
        jdbcTemplate.execute("SELECT pg_advisory_unlock_all()");
    }

    @Test
    void onlyOneConnectionAcquiresTheLockConcurrently() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return advisoryLock.tryAcquire(TEST_LOCK);
            });
            Future<Boolean> second = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return advisoryLock.tryAcquire(TEST_LOCK);
            });
            start.countDown();

            boolean acquiredFirst = first.get(5, TimeUnit.SECONDS);
            boolean acquiredSecond = second.get(5, TimeUnit.SECONDS);
            assertThat(acquiredFirst ^ acquiredSecond).isTrue();
            if (acquiredFirst) {
                advisoryLock.release(TEST_LOCK);
            }
            if (acquiredSecond) {
                advisoryLock.release(TEST_LOCK);
            }
        }
    }

    @Test
    void withLockHoldsOnOneSessionAndReleasesIt() throws Exception {
        long lockId = TEST_LOCK + 1;
        AtomicBoolean visibleToAnotherSession = new AtomicBoolean();

        Boolean ran = advisoryLock.withLock(lockId, () -> {
            try (Connection other = dataSource.getConnection();
                 PreparedStatement statement =
                         other.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, lockId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    visibleToAnotherSession.set(result.getBoolean(1));
                }
                return true;
            } catch (Exception failure) {
                throw new IllegalStateException("Could not probe the held lock", failure);
            }
        });

        assertThat(ran).isTrue();
        assertThat(visibleToAnotherSession).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_locks
                WHERE locktype = 'advisory' AND granted AND objid = ?
                """, Integer.class, lockId))
                .as("no session may keep the advisory lock after withLock returns")
                .isZero();
        try (Connection other = dataSource.getConnection()) {
            boolean acquiredAgain;
            try (PreparedStatement statement =
                         other.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, lockId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    acquiredAgain = result.getBoolean(1);
                }
            }
            try {
                assertThat(acquiredAgain)
                        .as("the lock must be released after withLock")
                        .isTrue();
            } finally {
                if (acquiredAgain) {
                    try (PreparedStatement release =
                                 other.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        release.setLong(1, lockId);
                        release.execute();
                    }
                }
            }
        }
    }
}

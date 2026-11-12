package com.nexachat.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void onlyOneConnectionAcquiresTheLockConcurrently() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        // A session advisory lock is reentrant. Pin two different physical
        // sessions instead of letting the pool reuse one session for both calls.
        try (Connection firstConnection = directConnection();
             Connection secondConnection = directConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            assertThat(backendPid(firstConnection)).isNotEqualTo(backendPid(secondConnection));
            ScheduledAdvisoryLock firstLock = onSession(firstConnection);
            ScheduledAdvisoryLock secondLock = onSession(secondConnection);
            try {
                Future<Boolean> first = executor.submit(() -> {
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    return firstLock.tryAcquire(TEST_LOCK);
                });
                Future<Boolean> second = executor.submit(() -> {
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    return secondLock.tryAcquire(TEST_LOCK);
                });
                start.countDown();

                boolean acquiredFirst = first.get(30, TimeUnit.SECONDS);
                boolean acquiredSecond = second.get(30, TimeUnit.SECONDS);
                assertThat(acquiredFirst ^ acquiredSecond)
                        .as("exactly one distinct session acquires while both sessions remain open")
                        .isTrue();
            } finally {
                // Release on the same sessions, including on assertion failure.
                firstLock.release(TEST_LOCK);
                secondLock.release(TEST_LOCK);
            }
        }
    }

    @Test
    void withLockHoldsOnOneSessionAndReleasesIt() throws Exception {
        long lockId = TEST_LOCK + 1;
        AtomicInteger holderPid = new AtomicInteger();

        Boolean ran = advisoryLock.withLock(lockId, () -> {
            // The observer must not compete for the application pool's last
            // connection while its callback is holding another pool connection.
            try (Connection other = directConnection();
                 PreparedStatement statement =
                         other.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                try (PreparedStatement owner = other.prepareStatement("""
                        SELECT pid FROM pg_locks
                        WHERE locktype = 'advisory' AND granted AND objid = ?
                          AND classid = 0 AND objsubid = 1
                          AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                        """)) {
                    owner.setLong(1, lockId);
                    try (ResultSet result = owner.executeQuery()) {
                        assertThat(result.next()).as("the callback runs while its session holds the lock").isTrue();
                        holderPid.set(result.getInt(1));
                        assertThat(result.next()).as("only one session owns this lock in this database").isFalse();
                    }
                }
                assertThat(backendPid(other)).isNotEqualTo(holderPid.get());
                statement.setLong(1, lockId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getBoolean(1))
                            .as("another physical session cannot acquire the held lock")
                            .isFalse();
                }
                return true;
            } catch (Exception failure) {
                throw new IllegalStateException("Could not probe the held lock", failure);
            }
        });

        assertThat(ran).isTrue();
        try (Connection other = directConnection()) {
            try (PreparedStatement statement = other.prepareStatement("""
                    SELECT count(*) FROM pg_locks
                    WHERE locktype = 'advisory' AND granted AND objid = ?
                      AND classid = 0 AND objsubid = 1 AND pid = ?
                      AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                    """)) {
                statement.setLong(1, lockId);
                statement.setInt(2, holderPid.get());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getInt(1))
                            .as("the owning session keeps no lock in this database after withLock returns")
                            .isZero();
                }
            }
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

    private static ScheduledAdvisoryLock onSession(Connection connection) {
        return new ScheduledAdvisoryLock(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static Connection directConnection() throws SQLException {
        return DriverManager.getConnection(
                env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"),
                env("IT_DB_USER", "nexachat"), env("IT_DB_PASSWORD", "nexachat"));
    }
}

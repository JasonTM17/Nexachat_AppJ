package com.nexachat.auth;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the shared reset's lock handling.
 *
 * <p>The full gate can overlap {@code TRUNCATE users CASCADE} with a session
 * that holds a lock on one of the cascaded tables and then reads another one
 * the truncate has already locked. PostgreSQL resolves that cycle by aborting
 * one participant; before the reset bounded its lock wait, it was the truncate,
 * and {@code @BeforeEach} failed with {@code deadlock detected} (full gate log
 * at {@code r4-336-s2.log:1843-1861}).
 *
 * <p>This test recreates the cycle deterministically: a transaction holds an
 * {@code AccessShare} lock on {@code refresh_tokens}, the reset's truncate
 * queues behind it while holding {@code users}, and the blocker then reads
 * {@code users}. The reset must still complete.
 */
class SecurityResetLockCycleIT extends SecurityITBase {

    @Test
    void resetSurvivesALockCycleWithAConcurrentReader() throws Exception {
        ExecutorService resetExecutor = Executors.newSingleThreadExecutor();
        ExecutorService blockerExecutor = Executors.newSingleThreadExecutor();
        try (Connection blocker = openConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.executeQuery("SELECT count(*) FROM refresh_tokens").close();
            }

            Future<Void> reset = resetExecutor.submit(() -> {
                resetUsers();
                return null;
            });
            awaitTruncateWaiting();

            Future<Integer> cycle = blockerExecutor.submit(() -> {
                try (Statement statement = blocker.createStatement();
                     ResultSet result = statement.executeQuery("SELECT count(*) FROM users")) {
                    result.next();
                    return result.getInt(1);
                }
            });
            try {
                // Either side can be the deadlock victim; both outcomes complete
                // the cycle, which is what the reset has to survive.
                cycle.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                // The blocker was the victim: its transaction is now aborted.
            } finally {
                blocker.rollback();
            }

            assertThat(reset.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            resetExecutor.shutdownNow();
            blockerExecutor.shutdownNow();
            resetExecutor.awaitTermination(10, TimeUnit.SECONDS);
            blockerExecutor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                environmentValue("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"),
                environmentValue("IT_DB_USER", "nexachat"),
                environmentValue("IT_DB_PASSWORD", "nexachat"));
    }

    private static void awaitTruncateWaiting() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = openConnection();
             PreparedStatement query = observer.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM pg_stat_activity
                         WHERE datname = current_database()
                           AND pid <> pg_backend_pid()
                           AND wait_event_type = 'Lock'
                           AND query ILIKE 'TRUNCATE users CASCADE%'
                     )
                     """)) {
            while (System.nanoTime() < deadline) {
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("TRUNCATE did not reach its lock wait");
    }

    private static String environmentValue(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

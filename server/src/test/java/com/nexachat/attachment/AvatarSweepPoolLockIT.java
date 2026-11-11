package com.nexachat.attachment;

import com.nexachat.ops.ScheduledLockIds;
import com.nexachat.testutil.TestWaits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Deterministic proof for the sweep / avatar-write connection-pool deadlock.
 *
 * <p>The sweep takes the session advisory lock on one pooled connection and
 * then used a second connection for its reference query. An avatar replacement
 * takes a transaction-scoped advisory wait on another connection. With a pool
 * of two, the sweep waits for the second connection while the avatar write
 * waits for the sweep lock: neither can finish. The sweep must run that query
 * on the connection that owns the lock.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=1500",
        "nexachat.storage.root=${java.io.tmpdir}/nexachat-avatar-sweep-pool-lock-it"
})
class AvatarSweepPoolLockIT {

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
    private AvatarOrphanSweepTask sweepTask;
    @Autowired
    private AvatarSweepProperties properties;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private AvatarReferenceCoordinator referenceCoordinator;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private CountDownLatch referencesEntered;
    private CountDownLatch releaseReferences;

    @BeforeEach
    void setUp() throws Exception {
        referencesEntered = new CountDownLatch(1);
        releaseReferences = new CountDownLatch(1);
        properties.setEnabled(true);
        properties.setDryRun(true);
        properties.setBatchSize(10);
        properties.setMinAgeHours(24);
        doAnswer(this::pauseBeforeReferenceQuery)
                .when(referenceCoordinator).referencedAvatarKeys();
        doAnswer(this::pauseBeforeReferenceQuery)
                .when(referenceCoordinator).referencedAvatarKeys(any(Connection.class));
    }

    @AfterEach
    void tearDown() {
        releaseReferences.countDown();
        properties.setEnabled(false);
        executor.shutdownNow();
    }

    @Test
    void sweepDoesNotNeedASecondConnectionWhileAnAvatarWriteWaitsForItsLock() throws Exception {
        TestWaits.until("the avatar sweep advisory lock to be free",
                java.time.Duration.ofSeconds(30), () -> grantedLockCount() == 0);

        Future<Optional<AvatarOrphanSweepTask.SweepResult>> sweep =
                executor.submit(sweepTask::runOnce);
        assertThat(referencesEntered.await(30, TimeUnit.SECONDS)).isTrue();

        CountDownLatch blockerAcquired = new CountDownLatch(1);
        Future<?> blocker = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    try (java.sql.PreparedStatement statement = connection.prepareStatement(
                            "SELECT pg_advisory_xact_lock(?)")) {
                        statement.setLong(1, ScheduledLockIds.AVATAR_ORPHAN_SWEEP);
                        statement.execute();
                    }
                    blockerAcquired.countDown();
                    return null;
                })));

        TestWaits.until("an avatar write to wait for the sweep advisory lock",
                () -> advisoryWaiterCount() > 0);
        assertThat(blockerAcquired.getCount())
                .as("the avatar write must still be waiting while the sweep queries")
                .isEqualTo(1);

        releaseReferences.countDown();

        assertThat(sweep.get(30, TimeUnit.SECONDS))
                .as("the sweep must not need a second pooled connection")
                .isPresent();
        assertThat(blockerAcquired.await(30, TimeUnit.SECONDS))
                .as("the sweep must release the advisory lock after its work")
                .isTrue();
        assertThat(blocker.get(30, TimeUnit.SECONDS)).isNull();
    }

    private Object pauseBeforeReferenceQuery(InvocationOnMock invocation) throws Throwable {
        referencesEntered.countDown();
        assertThat(releaseReferences.await(30, TimeUnit.SECONDS)).isTrue();
        return invocation.callRealMethod();
    }

    private int advisoryWaiterCount() {
        try (Connection probe = directConnection();
             PreparedStatement statement = probe.prepareStatement("""
                     SELECT count(*) FROM pg_locks
                     WHERE locktype = 'advisory' AND objid = ? AND NOT granted
                       AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                     """)) {
            statement.setLong(1, ScheduledLockIds.AVATAR_ORPHAN_SWEEP);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private int grantedLockCount() {
        try (Connection probe = directConnection();
             PreparedStatement statement = probe.prepareStatement("""
                     SELECT count(*) FROM pg_locks
                     WHERE locktype = 'advisory' AND granted AND objid = ?
                     """)) {
            statement.setLong(1, ScheduledLockIds.AVATAR_ORPHAN_SWEEP);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Connection directConnection() throws SQLException {
        return DriverManager.getConnection(
                env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"),
                env("IT_DB_USER", "nexachat"),
                env("IT_DB_PASSWORD", "nexachat"));
    }
}

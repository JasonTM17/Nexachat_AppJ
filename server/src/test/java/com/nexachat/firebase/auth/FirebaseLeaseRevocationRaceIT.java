package com.nexachat.firebase.auth;

import com.nexachat.auth.AccessTokenRevocationService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.config.FirebaseDatabaseUrl;
import com.nexachat.firebase.projection.FirebaseProjectionMetrics;
import com.nexachat.firebase.projection.FirebaseProjectionRetryQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cross-instance issue/revoke ordering against the same PostgreSQL user row. */
@SpringBootTest
@ActiveProfiles("it")
@Import(FirebaseLeaseRevocationRaceIT.RaceTestConfiguration.class)
class FirebaseLeaseRevocationRaceIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("nexachat.firebase.realtime-enabled", () -> "false");
        registry.add("nexachat.firebase.database-url", () -> "");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private UserRepository users;
    @Autowired
    private AccessTokenRevocationService revocations;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FakeFirebaseRtdbClient rtdb;
    @Autowired
    private FakeFirebaseAdminClient admin;
    @Autowired
    @Qualifier("firebaseBridgeServiceA")
    private FirebaseRealtimeAuthService serviceA;
    @Autowired
    @Qualifier("firebaseBridgeServiceB")
    private FirebaseRealtimeAuthService serviceB;

    private User user;
    private JwtPrincipal stalePrincipal;

    @BeforeEach
    void createAccountAndResetFakes() {
        String phone = "+8490" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L));
        user = users.saveAndFlush(User.create(phone, "x".repeat(60), "Firebase lease race"));
        Instant issuedAt = Instant.now().minusSeconds(30);
        stalePrincipal = new JwtPrincipal(user.getId(), issuedAt, Instant.now().plusSeconds(900));
        rtdb.reset();
        admin.reset();
    }

    @AfterEach
    void removeAccount() {
        if (user != null && users.existsById(user.getId())) {
            users.deleteById(user.getId());
        }
    }

    @Test
    void committedRevocationWaitsForAnOtherInstanceLeaseWriteAndStaleTokenCannotReissue() throws Exception {
        rtdb.holdNextLeaseWrite();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch revocationStarted = new CountDownLatch(1);
        Thread issueThread = new Thread(() -> capture(
                () -> serviceA.issue(stalePrincipal), failure), "firebase-instance-a-issue");
        Thread revocationThread = new Thread(() -> capture(() -> {
            revocationStarted.countDown();
            revocations.revoke(user.getId());
        }, failure), "firebase-instance-b-revoke");
        issueThread.setDaemon(true);
        revocationThread.setDaemon(true);

        issueThread.start();
        try {
            assertThat(rtdb.awaitLeaseWrite()).isTrue();
            int issueBackendPid = rtdb.issueBackendPid();
            revocationThread.start();
            assertThat(revocationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitDatabaseBlocker(issueBackendPid)).isTrue();
            assertThat(rtdb.operations()).containsExactly("lease-write-start");
        } finally {
            rtdb.releaseLeaseWrite();
            issueThread.join(10_000);
            if (revocationThread.getState() != Thread.State.NEW) {
                revocationThread.join(10_000);
            }
        }

        assertThat(issueThread.isAlive()).isFalse();
        assertThat(revocationThread.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(rtdb.operations()).containsExactly(
                "lease-write-start", "lease-write-finished", "user-revoked");

        assertThatThrownBy(() -> serviceB.issue(stalePrincipal))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ApiErrorCode.TOKEN_INVALID);
        assertThat(admin.customTokenCount()).isEqualTo(1);
        assertThat(rtdb.leaseWriteCount()).isEqualTo(1);
    }

    @Test
    void alreadyAuthenticatedBridgeRequestCannotMintAfterDeletionAtTheCutoffSecond() {
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        user.markDeleted(cutoff.plusMillis(1));
        user.invalidateAccessTokens(cutoff);
        users.saveAndFlush(user);

        // This principal represents a request that passed JWT authentication before deletion,
        // then reached the Firebase bridge after the tombstone and its inclusive cutoff committed.
        JwtPrincipal inFlightPrincipal = new JwtPrincipal(
                user.getId(), cutoff, cutoff.plusSeconds(900));

        assertThatThrownBy(() -> serviceA.issue(inFlightPrincipal))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ApiErrorCode.TOKEN_INVALID);
        assertThat(admin.customTokenCount()).isZero();
        assertThat(rtdb.leaseWriteCount()).isZero();
    }

    private boolean awaitDatabaseBlocker(int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                            + "WHERE wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)))",
                    Boolean.class,
                    blockerPid);
            if (Boolean.TRUE.equals(blocked)) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        return false;
    }

    private static void capture(Runnable operation, AtomicReference<Throwable> failure) {
        try {
            operation.run();
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceTestConfiguration {

        @Bean
        FakeFirebaseAdminClient fakeFirebaseAdminClient() {
            return new FakeFirebaseAdminClient();
        }

        @Bean
        FakeFirebaseRtdbClient fakeFirebaseRtdbClient(JdbcTemplate jdbcTemplate) {
            return new FakeFirebaseRtdbClient(jdbcTemplate);
        }

        @Bean
        FirebaseBridgeLock firebaseBridgeLockA() {
            return new FirebaseBridgeLock();
        }

        @Bean
        FirebaseBridgeLock firebaseBridgeLockB() {
            return new FirebaseBridgeLock();
        }

        @Bean("firebaseBridgeServiceA")
        FirebaseRealtimeAuthService firebaseBridgeServiceA(UserRepository users,
                                                          ConversationMemberRepository members,
                                                          FakeFirebaseAdminClient admin,
                                                          FakeFirebaseRtdbClient rtdb,
                                                          @Qualifier("firebaseBridgeLockA") FirebaseBridgeLock locks) {
            return new FirebaseRealtimeAuthService(users, members, admin, rtdb, locks,
                    FirebaseDatabaseUrl.parse("https://race-test.firebaseio.com"), Clock.systemUTC());
        }

        @Bean("firebaseBridgeServiceB")
        FirebaseRealtimeAuthService firebaseBridgeServiceB(UserRepository users,
                                                          ConversationMemberRepository members,
                                                          FakeFirebaseAdminClient admin,
                                                          FakeFirebaseRtdbClient rtdb,
                                                          @Qualifier("firebaseBridgeLockB") FirebaseBridgeLock locks) {
            return new FirebaseRealtimeAuthService(users, members, admin, rtdb, locks,
                    FirebaseDatabaseUrl.parse("https://race-test.firebaseio.com"), Clock.systemUTC());
        }

        @Bean
        FirebaseSessionRevocationListener firebaseSessionRevocationListenerB(
                @Qualifier("firebaseBridgeLockB") FirebaseBridgeLock locks,
                FakeFirebaseRtdbClient rtdb,
                FakeFirebaseAdminClient admin,
                ConversationMemberRepository members) {
            return new FirebaseSessionRevocationListener(locks, rtdb, admin, members,
                    new FirebaseProjectionRetryQueue(Clock.systemUTC(),
                            new FirebaseProjectionMetrics(new SimpleMeterRegistry())));
        }
    }

    static final class FakeFirebaseAdminClient implements FirebaseAdminClient {

        private final AtomicInteger customTokenCount = new AtomicInteger();

        @Override
        public String createCustomToken(String uid, Map<String, Object> additionalClaims) {
            customTokenCount.incrementAndGet();
            return "fake-custom-token";
        }

        @Override
        public void revokeRefreshTokens(String uid) {
            // The faked auth provider records no token material.
        }

        int customTokenCount() {
            return customTokenCount.get();
        }

        void reset() {
            customTokenCount.set(0);
        }
    }

    static final class FakeFirebaseRtdbClient implements FirebaseRtdbClient {

        private final JdbcTemplate jdbcTemplate;
        private final ConcurrentLinkedQueue<String> operations = new ConcurrentLinkedQueue<>();
        private final AtomicInteger leaseWriteCount = new AtomicInteger();
        private final AtomicInteger issueBackendPid = new AtomicInteger();
        private volatile CountDownLatch leaseWriteEntered = new CountDownLatch(0);
        private volatile CountDownLatch allowLeaseWrite = new CountDownLatch(0);
        private volatile boolean holdWrite;

        FakeFirebaseRtdbClient(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        void holdNextLeaseWrite() {
            leaseWriteEntered = new CountDownLatch(1);
            allowLeaseWrite = new CountDownLatch(1);
            holdWrite = true;
        }

        boolean awaitLeaseWrite() throws InterruptedException {
            return leaseWriteEntered.await(5, TimeUnit.SECONDS);
        }

        void releaseLeaseWrite() {
            allowLeaseWrite.countDown();
        }

        int issueBackendPid() {
            return issueBackendPid.get();
        }

        List<String> operations() {
            return List.copyOf(operations);
        }

        int leaseWriteCount() {
            return leaseWriteCount.get();
        }

        @Override
        public Long readSessionLease(UUID userId, UUID bridgeId) {
            return null;
        }

        @Override
        public void writeSessionLease(UUID userId, UUID bridgeId, long leaseUntilMillis,
                                      Map<UUID, Long> conversationLeaseUntilMillis) {
            issueBackendPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            leaseWriteCount.incrementAndGet();
            operations.add("lease-write-start");
            if (holdWrite) {
                leaseWriteEntered.countDown();
                try {
                    if (!allowLeaseWrite.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release fake Firebase write");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fake Firebase write was interrupted");
                } finally {
                    holdWrite = false;
                }
            }
            operations.add("lease-write-finished");
        }

        @Override
        public void deleteBridge(UUID userId, UUID bridgeId) {
        }

        @Override
        public void revokeUser(UUID userId, List<UUID> conversationIds) {
            operations.add("user-revoked");
        }

        void reset() {
            operations.clear();
            leaseWriteCount.set(0);
            issueBackendPid.set(0);
            holdWrite = false;
            leaseWriteEntered = new CountDownLatch(0);
            allowLeaseWrite = new CountDownLatch(0);
        }
    }
}

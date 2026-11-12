package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.recovery.EmailOtpService;
import com.nexachat.recovery.OtpMail;
import com.nexachat.recovery.OtpPurpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deterministic PostgreSQL schedules for cross-session revocation and lineage. */
@Import(RefreshRotationReplayIT.ReplayTestConfiguration.class)
class RefreshRevocationRaceIT extends SecurityITBase {

    private static final String TEST_KEY = syntheticReplayKey();

    @Autowired private RefreshRotationReplayIT.ReplayTestClock replayClock;
    @Autowired private RefreshRotationReplayIT.ReplayBoundaryProbe boundaryProbe;
    @Autowired private RefreshReplayProperties replayProperties;
    @Autowired private DataSource dataSource;
    @Autowired private EmailOtpService emailOtpService;

    private static String syntheticReplayKey() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        return Base64.getEncoder().encodeToString(key);
    }

    @BeforeEach
    void configureDeterministicBoundaries() {
        boundaryProbe.clear();
        replayClock.set(Instant.now().plusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        replayProperties.setKey(TEST_KEY);
    }

    @AfterEach
    void clearBoundaries() {
        boundaryProbe.clear();
        replayProperties.setKey(TEST_KEY);
    }

    @Test
    void legacyBulkUpdateScheduleDemonstratesThePreFixInvisibleChild() throws Exception {
        Registered first = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552801"));
        Registered rotating = login(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552801"));
        UUID childId = UUID.randomUUID();
        String childHash = TokenService.sha256Hex(UUID.randomUUID().toString());
        Instant now = replayClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection rotation = dataSource.getConnection()) {
            rotation.setAutoCommit(false);
            rotation.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (PreparedStatement update = rotation.prepareStatement(
                    "UPDATE refresh_tokens SET revoked_at = ?, replaced_by = ? WHERE token_hash = ?")) {
                update.setTimestamp(1, Timestamp.from(now));
                update.setObject(2, childId);
                update.setString(3, TokenService.sha256Hex(rotating.refreshToken()));
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insert = rotation.prepareStatement(
                    "INSERT INTO refresh_tokens(id, user_id, token_hash, device_label, created_at, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, childId);
                insert.setObject(2, rotating.userId());
                insert.setString(3, childHash);
                insert.setString(4, "synthetic-race");
                insert.setTimestamp(5, Timestamp.from(now));
                insert.setTimestamp(6, Timestamp.from(now.plusSeconds(86_400)));
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }

            CountDownLatch revokerReady = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger revokerPid = new java.util.concurrent.atomic.AtomicInteger();
            try {
                Future<Integer> oldBulkUpdate = executor.submit(() -> {
                    try (Connection revocation = dataSource.getConnection()) {
                        revocation.setAutoCommit(false);
                        revocation.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                        try (PreparedStatement pid = revocation.prepareStatement("SELECT pg_backend_pid()")) {
                            try (ResultSet result = pid.executeQuery()) {
                                result.next();
                                revokerPid.set(result.getInt(1));
                            }
                        }
                        revokerReady.countDown();
                        try (PreparedStatement update = revocation.prepareStatement(
                                "UPDATE refresh_tokens SET revoked_at = ? "
                                        + "WHERE user_id = ? AND revoked_at IS NULL")) {
                            update.setTimestamp(1, Timestamp.from(now));
                            update.setObject(2, rotating.userId());
                            int rows = update.executeUpdate();
                            revocation.commit();
                            return rows;
                        }
                    }
                });

                await(revokerReady);
                awaitDatabaseLockWait(rotation, revokerPid.get());
                rotation.commit();
                oldBulkUpdate.get(20, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        } finally {
            // The connection must be closed (releasing its row locks) before
            // waiting for the revoker, which may be blocked on those locks.
            awaitWorkers(executor);
        }

        Integer oldParentRevoked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? AND revoked_at IS NOT NULL",
                Integer.class, TokenService.sha256Hex(rotating.refreshToken()));
        Integer invisibleChildSurvived = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE id = ? AND revoked_at IS NULL",
                Integer.class, childId);
        assertThat(oldParentRevoked).isEqualTo(1);
        assertThat(invisibleChildSurvived).isEqualTo(1);
        assertThat(liveRows(first.userId())).isEqualTo(1);
    }

    @Test
    void sharedUserLockMakesLogoutAllRevokeAChildCommittedByAnotherRotation() throws Exception {
        Registered first = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552802"));
        Registered rotating = login(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552802"));
        String key = UUID.randomUUID().toString();
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch releaseRotation = new CountDownLatch(1);
        CountDownLatch logoutWaiting = new CountDownLatch(1);
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.AFTER_SUCCESSOR_CREATED,
                RefreshReplayConfiguration.Operation.ROTATE, event -> {
                    childCreated.countDown();
                    await(releaseRotation);
                });
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK,
                RefreshReplayConfiguration.Operation.LOGOUT_ALL, event -> logoutWaiting.countDown());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> rotation = executor.submit(() -> postRefresh(rotating.refreshToken(), key).andReturn());
            await(childCreated);
            Future<MvcResult> logout = executor.submit(() -> postJson(
                    "/api/v1/auth/logout-all", Map.of(), first.accessToken()).andReturn());
            await(logoutWaiting);
            releaseRotation.countDown();

            assertThat(rotation.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(logout.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
        } finally {
            releaseRotation.countDown();
            shutdownWorkers(executor);
        }

        assertThat(liveRows(first.userId())).isZero();
        assertThat(eventIndex(RefreshReplayConfiguration.Boundary.AFTER_RESPONSE_CONSTRUCTION,
                RefreshReplayConfiguration.Operation.ROTATE))
                .isLessThan(eventIndex(RefreshReplayConfiguration.Boundary.AFTER_USER_LOCK,
                        RefreshReplayConfiguration.Operation.LOGOUT_ALL));
    }

    @Test
    void passwordResetHoldingUserLockMakesConcurrentRefreshFailAfterRevocation() throws Exception {
        Registered user = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552813"));
        jdbcTemplate.update("UPDATE users SET email = ?, email_verified_at = ? WHERE id = ?",
                "reset-race@example.com", Timestamp.from(Instant.now()), user.userId());
        OtpMail reset = emailOtpService.issueForPublicRequest(user.userId(), OtpPurpose.RESET_PASSWORD)
                .orElseThrow();

        CountDownLatch resetOwnsUserLock = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        CountDownLatch rotationAtUserLock = new CountDownLatch(1);
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK,
                RefreshReplayConfiguration.Operation.LOGOUT_ALL, event -> {
                    resetOwnsUserLock.countDown();
                    await(releaseReset);
                });
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK,
                RefreshReplayConfiguration.Operation.ROTATE, event -> rotationAtUserLock.countDown());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> passwordReset = executor.submit(() -> postJson(
                    "/api/v1/auth/password-reset/confirm",
                    Map.of("phone", ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552813"), "code", reset.code(), "newPassword", "reset-race123"))
                    .andReturn().getResponse().getStatus());
            await(resetOwnsUserLock);

            Future<MvcResult> rotation = executor.submit(() -> postRefresh(user.refreshToken(), null).andReturn());
            await(rotationAtUserLock);
            awaitUserRowLockWait();
            releaseReset.countDown();

            assertThat(passwordReset.get(30, TimeUnit.SECONDS)).isEqualTo(204);
            MvcResult rotationResult = rotation.get(30, TimeUnit.SECONDS);
            assertThat(rotationResult.getResponse().getStatus()).isEqualTo(401);
            assertThat(response(rotationResult).path("error").path("code").asText())
                    .isIn("TOKEN_INVALID", "TOKEN_REUSED");
        } finally {
            releaseReset.countDown();
            shutdownWorkers(executor);
        }

        assertThat(liveRows(user.userId())).isZero();
        getWithAuth("/api/v1/users/me", user.accessToken()).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", Map.of("phone", ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552813"), "password", "secret123"))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", Map.of("phone", ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552813"), "password", "reset-race123"))
                .andExpect(status().isOk());
    }

    @Test
    void reuseRevocationWaitsForOtherSessionRotationAndIncludesItsChild() throws Exception {
        Registered reused = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552803"));
        assertThat(postRefresh(reused.refreshToken(), null).andReturn().getResponse().getStatus()).isEqualTo(200);
        Registered rotating = login(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552803"));
        String key = UUID.randomUUID().toString();
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch releaseRotation = new CountDownLatch(1);
        CountDownLatch secondRotationWaiting = new CountDownLatch(1);
        AtomicInteger beforeLocks = new AtomicInteger();
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.AFTER_SUCCESSOR_CREATED,
                RefreshReplayConfiguration.Operation.ROTATE, event -> {
                    childCreated.countDown();
                    await(releaseRotation);
                });
        boundaryProbe.arm(RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK,
                RefreshReplayConfiguration.Operation.ROTATE, event -> {
                    if (beforeLocks.incrementAndGet() == 2) {
                        secondRotationWaiting.countDown();
                    }
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> rotation = executor.submit(() -> postRefresh(rotating.refreshToken(), key).andReturn());
            await(childCreated);
            Future<MvcResult> reuse = executor.submit(() -> postRefresh(reused.refreshToken(), null).andReturn());
            await(secondRotationWaiting);
            releaseRotation.countDown();

            assertThat(rotation.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            MvcResult reusedResult = reuse.get(30, TimeUnit.SECONDS);
            assertThat(reusedResult.getResponse().getStatus()).isEqualTo(401);
            assertThat(response(reusedResult).path("error").path("code").asText()).isEqualTo("TOKEN_REUSED");
        } finally {
            releaseRotation.countDown();
            shutdownWorkers(executor);
        }
        assertThat(liveRows(reused.userId())).isZero();
    }

    @Test
    void stalePredecessorLogoutRevokesItsFullChainAndPreservesAnotherDevice() throws Exception {
        Registered first = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552804"));
        Registered otherDevice = login(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552804"));
        JsonNode second = response(postRefresh(first.refreshToken(), null).andExpect(status().isOk()).andReturn());
        JsonNode third = response(postRefresh(second.path("refreshToken").asText(), null)
                .andExpect(status().isOk()).andReturn());

        postJson("/api/v1/auth/logout", Map.of("refreshToken", first.refreshToken()), first.accessToken())
                .andExpect(status().isNoContent());

        assertThat(isRevoked(first.refreshToken())).isTrue();
        assertThat(isRevoked(second.path("refreshToken").asText())).isTrue();
        assertThat(isRevoked(third.path("refreshToken").asText())).isTrue();
        assertThat(isRevoked(otherDevice.refreshToken())).isFalse();
    }

    @Test
    void logoutOnDistinctDevicePreservesCommittedReplayForSurvivingLineage() throws Exception {
        Registered rotatingDevice = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552812"));
        Registered logoutDevice = login(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552812"));
        String requestKey = UUID.randomUUID().toString();

        JsonNode committed = response(postRefresh(rotatingDevice.refreshToken(), requestKey)
                .andExpect(status().isOk()).andReturn());
        postJson("/api/v1/auth/logout", Map.of("refreshToken", logoutDevice.refreshToken()),
                logoutDevice.accessToken()).andExpect(status().isNoContent());

        JsonNode recovered = response(postRefresh(rotatingDevice.refreshToken(), requestKey)
                .andExpect(status().isOk()).andReturn());
        assertThat(recovered).isEqualTo(committed);
        assertThat(isRevoked(recovered.path("refreshToken").asText())).isFalse();
        assertThat(liveRows(rotatingDevice.userId())).isEqualTo(1);
    }

    @Test
    void concurrentRotateAndStaleLogoutSerializeBeforeInvalidatingReplay() throws Exception {
        Registered first = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552805"));
        Registered otherDevice = login(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552805"));
        String key = UUID.randomUUID().toString();
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch releaseRotation = new CountDownLatch(1);
        CountDownLatch logoutWaiting = new CountDownLatch(1);
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.AFTER_SUCCESSOR_CREATED,
                RefreshReplayConfiguration.Operation.ROTATE, event -> {
                    childCreated.countDown();
                    await(releaseRotation);
                });
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK,
                RefreshReplayConfiguration.Operation.LOGOUT, event -> logoutWaiting.countDown());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> rotation = executor.submit(() -> postRefresh(first.refreshToken(), key).andReturn());
            await(childCreated);
            Future<MvcResult> logout = executor.submit(() -> postJson(
                    "/api/v1/auth/logout", Map.of("refreshToken", first.refreshToken()), first.accessToken())
                    .andReturn());
            await(logoutWaiting);
            releaseRotation.countDown();

            assertThat(rotation.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(logout.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
        } finally {
            releaseRotation.countDown();
            shutdownWorkers(executor);
        }

        assertThat(isRevoked(first.refreshToken())).isTrue();
        Integer childRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND replaced_by IS NULL "
                        + "AND revoked_at IS NOT NULL",
                Integer.class, first.userId());
        assertThat(childRows).isGreaterThanOrEqualTo(1);
        assertThat(isRevoked(otherDevice.refreshToken())).isFalse();
        assertThat(eventIndex(RefreshReplayConfiguration.Boundary.AFTER_RESPONSE_CONSTRUCTION,
                RefreshReplayConfiguration.Operation.ROTATE))
                .isLessThan(eventIndex(RefreshReplayConfiguration.Boundary.AFTER_USER_LOCK,
                        RefreshReplayConfiguration.Operation.LOGOUT));
        postRefresh(first.refreshToken(), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"));
    }

    @Test
    void unknownOrForeignLogoutTokenRemainsAnOwnershipCheckedNoOp() throws Exception {
        Registered owner = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552806"));
        Registered foreign = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552807"));

        postJson("/api/v1/auth/logout", Map.of("refreshToken", foreign.refreshToken()), owner.accessToken())
                .andExpect(status().isNoContent());
        assertThat(isRevoked(foreign.refreshToken())).isFalse();
        assertThat(isRevoked(owner.refreshToken())).isFalse();
    }

    @Test
    void passwordRetryWithOldBodyPreservesRotatedDescendantAndRevokesOtherDevices() throws Exception {
        Registered current = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552808"));
        Registered otherDevice = login(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552808"));
        JsonNode rotated = response(postRefresh(current.refreshToken(), UUID.randomUUID().toString())
                .andExpect(status().isOk()).andReturn());

        patchWithAuth("/api/v1/auth/password", Map.of(
                        "oldPassword", "secret123",
                        "newPassword", "new-secret456",
                        "refreshToken", current.refreshToken()), current.accessToken())
                .andExpect(status().isNoContent());

        assertThat(isRevoked(current.refreshToken())).isTrue();
        assertThat(isRevoked(rotated.path("refreshToken").asText())).isFalse();
        assertThat(isRevoked(otherDevice.refreshToken())).isTrue();
    }

    @Test
    void unknownOrForeignPasswordKeepValueExemptsNoSession() throws Exception {
        Registered unknown = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552809"));
        login(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552809"));
        patchWithAuth("/api/v1/auth/password", Map.of(
                        "oldPassword", "secret123",
                        "newPassword", "new-secret456",
                        "refreshToken", "unknown-synthetic-token"), unknown.accessToken())
                .andExpect(status().isNoContent());
        assertThat(liveRows(unknown.userId())).isZero();

        Registered owner = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552810"));
        Registered foreign = register(ItPhoneRanges.phoneFor(RefreshRevocationRaceIT.class, "+84905552811"));
        patchWithAuth("/api/v1/auth/password", Map.of(
                        "oldPassword", "secret123",
                        "newPassword", "new-secret456",
                        "refreshToken", foreign.refreshToken()), owner.accessToken())
                .andExpect(status().isNoContent());
        assertThat(liveRows(owner.userId())).isZero();
        assertThat(isRevoked(foreign.refreshToken())).isFalse();
    }

    private ResultActions postRefresh(String token, String key) throws Exception {
        var request = post("/api/v1/auth/refresh").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", token)));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int liveRows(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
    }

    private boolean isRevoked(String token) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE token_hash = ?",
                Boolean.class, TokenService.sha256Hex(token)));
    }

    private int eventIndex(RefreshReplayConfiguration.Boundary boundary,
                           RefreshReplayConfiguration.Operation operation) {
        List<RefreshRotationReplayIT.ProbeEvent> events = boundaryProbe.events();
        for (int index = 0; index < events.size(); index++) {
            RefreshRotationReplayIT.ProbeEvent event = events.get(index);
            if (event.boundary() == boundary && event.operation() == operation) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void awaitDatabaseLockWait(Connection observer, int backendPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (PreparedStatement query = observer.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                        + "WHERE pid = ? AND wait_event_type = 'Lock')")) {
            query.setInt(1, backendPid);
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
        throw new AssertionError("Legacy bulk UPDATE did not reach its planned row-lock wait");
    }

    private void awaitUserRowLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = DriverManager.getConnection(
                environmentValue("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"),
                environmentValue("IT_DB_USER", "nexachat"),
                environmentValue("IT_DB_PASSWORD", "nexachat"));
             PreparedStatement query = observer.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM pg_stat_activity
                         WHERE datname = current_database()
                           AND pid <> pg_backend_pid()
                           AND wait_event_type = 'Lock'
                           AND query ILIKE '%users%'
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
        throw new AssertionError("Refresh rotation did not reach the password-reset user-row lock");
    }

    private static String environmentValue(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void shutdownWorkers(ExecutorService executor) {
        executor.shutdownNow();
        awaitWorkers(executor);
    }

    /**
     * A method must not return while its request threads still own a
     * connection or a row lock: the next test's {@code TRUNCATE users CASCADE}
     * would otherwise race a transaction that has no owner left to finish it.
     */
    private static void awaitWorkers(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Worker threads did not stop within 10 s");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while stopping worker threads", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the planned user-row lock boundary");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the planned lock boundary", exception);
        }
    }
}

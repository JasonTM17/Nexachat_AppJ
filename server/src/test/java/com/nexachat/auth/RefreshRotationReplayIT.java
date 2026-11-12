package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression coverage for durable recovery of a committed keyed refresh. */
@Import(RefreshRotationReplayIT.ReplayTestConfiguration.class)
class RefreshRotationReplayIT extends SecurityITBase {

    private static final String SYNTHETIC_KEY = syntheticReplayKey();

    @org.springframework.beans.factory.annotation.Autowired
    private ReplayTestClock replayClock;
    @org.springframework.beans.factory.annotation.Autowired
    private ReplayBoundaryProbe boundaryProbe;
    @org.springframework.beans.factory.annotation.Autowired
    private RefreshReplayProperties replayProperties;
    @org.springframework.beans.factory.annotation.Autowired
    private RefreshReplayCleanup replayCleanup;

    private static String syntheticReplayKey() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        return Base64.getEncoder().encodeToString(key);
    }

    @BeforeEach
    void setReplayClock() {
        boundaryProbe.clear();
        replayClock.set(Instant.now().plusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        replayProperties.setKey(SYNTHETIC_KEY);
    }

    @AfterEach
    void clearReplayProbe() {
        boundaryProbe.clear();
        replayProperties.setKey(SYNTHETIC_KEY);
    }

    @Test
    void retryAfterLostResponseReturnsTheOriginalResponse() throws Exception {
        Registered session = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552751"));
        String key = UUID.randomUUID().toString();

        MvcResult first = postRefresh(session.refreshToken(), key)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());

        // Discard the first response, then send the same logical operation.
        MvcResult retry = postRefresh(session.refreshToken(), key)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode retryBody = objectMapper.readTree(retry.getResponse().getContentAsString());

        assertThat(firstBody.equals(retryBody)).isTrue();
        assertThat(refreshRowCount(session.userId())).isEqualTo(2);
        assertThat(liveRowCount(session.userId())).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsShareOneCommittedPair() throws Exception {
        Registered session = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552752"));
        String key = UUID.randomUUID().toString();
        CountDownLatch firstOwnsUserLock = new CountDownLatch(1);
        CountDownLatch secondIsWaitingForUserLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger beforeLock = new AtomicInteger();
        AtomicInteger afterLock = new AtomicInteger();
        boundaryProbe.arm(RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK,
                RefreshReplayConfiguration.Operation.ROTATE, event -> {
                    if (beforeLock.incrementAndGet() == 2) {
                        secondIsWaitingForUserLock.countDown();
                    }
                });
        boundaryProbe.arm(RefreshReplayConfiguration.Boundary.AFTER_USER_LOCK,
                RefreshReplayConfiguration.Operation.ROTATE, event -> {
                    if (afterLock.incrementAndGet() == 1) {
                        firstOwnsUserLock.countDown();
                        await(releaseFirst);
                    }
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> postRefresh(session.refreshToken(), key).andReturn());
            await(firstOwnsUserLock);
            Future<MvcResult> second = executor.submit(() -> postRefresh(session.refreshToken(), key).andReturn());
            await(secondIsWaitingForUserLock);
            releaseFirst.countDown();

            MvcResult a = first.get(30, TimeUnit.SECONDS);
            MvcResult b = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 200);
            JsonNode bodyA = objectMapper.readTree(a.getResponse().getContentAsString());
            JsonNode bodyB = objectMapper.readTree(b.getResponse().getContentAsString());
            assertThat(bodyA.equals(bodyB)).isTrue();
            assertThat(refreshRowCount(session.userId())).isEqualTo(2);
            assertThat(liveRowCount(session.userId())).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            shutdownWorkers(executor);
        }
    }

    @Test
    void distinctOrMissingKeyOnSpentPredecessorKeepsStrictReuseBehavior() throws Exception {
        Registered distinct = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552753"));
        String firstKey = UUID.randomUUID().toString();
        postRefresh(distinct.refreshToken(), firstKey).andExpect(status().isOk());
        postRefresh(distinct.refreshToken(), UUID.randomUUID().toString())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REUSED"));
        assertThat(liveRowCount(distinct.userId())).isZero();

        Registered missing = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552754"));
        postRefresh(missing.refreshToken(), UUID.randomUUID().toString()).andExpect(status().isOk());
        postRefresh(missing.refreshToken(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REUSED"));
        assertThat(liveRowCount(missing.userId())).isZero();
    }

    @Test
    void sameKeyIsScopedToEachPredecessorAndAccount() throws Exception {
        Registered accountA = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552755"));
        Registered secondDeviceA = login(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552755"));
        Registered accountB = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552756"));
        String key = UUID.randomUUID().toString();

        JsonNode responseA = response(postRefresh(accountA.refreshToken(), key).andExpect(status().isOk()).andReturn());
        JsonNode responseSecondDeviceA = response(postRefresh(secondDeviceA.refreshToken(), key)
                .andExpect(status().isOk()).andReturn());
        JsonNode responseB = response(postRefresh(accountB.refreshToken(), key).andExpect(status().isOk()).andReturn());

        assertThat(responseA.path("user").path("id").asText()).isEqualTo(accountA.userId().toString());
        assertThat(responseSecondDeviceA.path("user").path("id").asText()).isEqualTo(accountA.userId().toString());
        assertThat(responseB.path("user").path("id").asText()).isEqualTo(accountB.userId().toString());
        assertThat(responseA.equals(responseSecondDeviceA)).isFalse();
        assertThat(responseA.equals(responseB)).isFalse();
        assertThat(liveRowCount(accountA.userId())).isEqualTo(2);
        assertThat(liveRowCount(accountB.userId())).isEqualTo(1);
    }

    @Test
    void malformedBlankNonV4OrNoncanonicalKeysFailBeforeMutation() throws Exception {
        Registered session = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552757"));
        List<String> malformed = List.of("", " ", "123e4567-e89b-12d3-a456-426614174000",
                "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "a".repeat(37));

        for (String key : malformed) {
            postRefresh(session.refreshToken(), key)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        assertThat(refreshRowCount(session.userId())).isEqualTo(1);
        assertThat(liveRowCount(session.userId())).isEqualTo(1);
        Integer replayRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND replay_key_digest IS NOT NULL",
                Integer.class, session.userId());
        assertThat(replayRows).isZero();
    }

    @Test
    void replayRecoveryUsesDatabaseTimeDespiteApplicationClockSkew() throws Exception {
        Registered session = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552758"));
        String key = UUID.randomUUID().toString();
        postRefresh(session.refreshToken(), key).andExpect(status().isOk());
        Instant rotatedAt = jdbcTemplate.queryForObject(
                "SELECT replay_rotated_at FROM refresh_tokens WHERE token_hash = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), TokenService.sha256Hex(session.refreshToken()));
        Instant replayUntil = jdbcTemplate.queryForObject(
                "SELECT replay_until FROM refresh_tokens WHERE token_hash = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), TokenService.sha256Hex(session.refreshToken()));
        Instant databaseNow = jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
        assertThat(rotatedAt).isAfter(databaseNow.minusSeconds(2)).isBefore(databaseNow.plusSeconds(1));
        assertThat(replayUntil).isEqualTo(rotatedAt.plusSeconds(30));

        replayClock.set(Instant.parse("2100-01-01T00:00:00Z"));
        postRefresh(session.refreshToken(), key).andExpect(status().isOk());
    }

    @Test
    void clearedExpiredCiphertextRetainsKnownKeyTombstone() throws Exception {
        Registered session = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552759"));
        String key = UUID.randomUUID().toString();
        postRefresh(session.refreshToken(), key).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE refresh_tokens SET replay_until = clock_timestamp() "
                        + "WHERE token_hash = ?", TokenService.sha256Hex(session.refreshToken()));

        postRefresh(session.refreshToken(), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"));
        assertThat(replayCleanup.scrubExpiredPayloads()).isEqualTo(1);
        Integer tombstones = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? AND replay_key_digest IS NOT NULL "
                        + "AND replay_ciphertext IS NULL AND replay_invalidated",
                Integer.class, TokenService.sha256Hex(session.refreshToken()));
        assertThat(tombstones).isEqualTo(1);
        postRefresh(session.refreshToken(), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"));
        assertThat(refreshRowCount(session.userId())).isEqualTo(2);
        assertThat(liveRowCount(session.userId())).isEqualTo(1);
    }

    @Test
    void cleanupUsesDatabaseTimeInsteadOfApplicationClock() throws Exception {
        Registered session = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552773"));
        postRefresh(session.refreshToken(), UUID.randomUUID().toString()).andExpect(status().isOk());
        replayClock.set(Instant.parse("2100-01-01T00:00:00Z"));

        assertThat(replayCleanup.scrubExpiredPayloads()).isZero();
        Integer payloadsBeforeDeadline = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? AND replay_ciphertext IS NOT NULL",
                Integer.class, TokenService.sha256Hex(session.refreshToken()));
        assertThat(payloadsBeforeDeadline).isEqualTo(1);

        jdbcTemplate.update("UPDATE refresh_tokens SET replay_rotated_at = clock_timestamp() - INTERVAL '31 seconds', "
                        + "replay_until = clock_timestamp() - INTERVAL '1 second' WHERE token_hash = ?",
                TokenService.sha256Hex(session.refreshToken()));
        assertThat(replayCleanup.scrubExpiredPayloads()).isEqualTo(1);
    }

    @Test
    void advancedLoggedOutOrExpiredSuccessorCannotBeRecovered() throws Exception {
        Registered advanced = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552760"));
        String firstKey = UUID.randomUUID().toString();
        JsonNode firstResponse = response(postRefresh(advanced.refreshToken(), firstKey)
                .andExpect(status().isOk()).andReturn());
        postRefresh(firstResponse.path("refreshToken").asText(), UUID.randomUUID().toString())
                .andExpect(status().isOk());
        postRefresh(advanced.refreshToken(), firstKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"));

        Registered loggedOut = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552761"));
        String logoutKey = UUID.randomUUID().toString();
        JsonNode logoutResponse = response(postRefresh(loggedOut.refreshToken(), logoutKey)
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + logoutResponse.path("accessToken").asText())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", logoutResponse.path("refreshToken").asText()))))
                .andExpect(status().isNoContent());
        postRefresh(loggedOut.refreshToken(), logoutKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"));

        Registered expired = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552762"));
        String expiredKey = UUID.randomUUID().toString();
        postRefresh(expired.refreshToken(), expiredKey).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE refresh_tokens child SET expires_at = clock_timestamp() "
                        + "FROM refresh_tokens parent WHERE parent.token_hash = ? AND child.id = parent.replaced_by",
                TokenService.sha256Hex(expired.refreshToken()));
        postRefresh(expired.refreshToken(), expiredKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"));
    }

    @Test
    void missingEncryptionKeyUsesLegacyStrictRotation() throws Exception {
        assertUnavailableEncryptionKeyUsesLegacyStrictRotation("", ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552763"));
    }

    @Test
    void malformedEncryptionKeyUsesLegacyStrictRotation() throws Exception {
        assertUnavailableEncryptionKeyUsesLegacyStrictRotation("not-base64", ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552772"));
    }

    @Test
    void knownExpiredTokenRevokesOtherSessionsAndInvalidatesTheirCachedResponses() throws Exception {
        Registered cached = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552769"));
        Registered expiring = login(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552769"));
        String key = UUID.randomUUID().toString();
        postRefresh(cached.refreshToken(), key).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = clock_timestamp() WHERE token_hash = ?",
                TokenService.sha256Hex(expiring.refreshToken()));

        postRefresh(expiring.refreshToken(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
        postRefresh(cached.refreshToken(), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"));
        assertThat(liveRowCount(cached.userId())).isZero();
    }

    @Test
    void changedKeyAndTamperedPayloadReturnNonrevokingRecoveryUnavailable() throws Exception {
        Registered changed = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552764"));
        String changedKey = UUID.randomUUID().toString();
        postRefresh(changed.refreshToken(), changedKey).andExpect(status().isOk());
        replayProperties.setKey(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        postRefresh(changed.refreshToken(), changedKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        assertThat(liveRowCount(changed.userId())).isEqualTo(1);

        replayProperties.setKey(SYNTHETIC_KEY);
        Registered tampered = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552765"));
        String tamperedKey = UUID.randomUUID().toString();
        postRefresh(tampered.refreshToken(), tamperedKey).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE refresh_tokens SET replay_ciphertext = "
                        + "set_byte(replay_ciphertext, 0, (get_byte(replay_ciphertext, 0) + 1) % 256) "
                        + "WHERE token_hash = ?", TokenService.sha256Hex(tampered.refreshToken()));
        postRefresh(tampered.refreshToken(), tamperedKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFRESH_RECOVERY_UNAVAILABLE"));
        assertThat(liveRowCount(tampered.userId())).isEqualTo(1);
    }

    @Test
    void rollbackBeforeCommitAllowsOneRetryAndPostCommitFaultRecoversOriginal() throws Exception {
        Registered rollback = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552766"));
        String rollbackKey = UUID.randomUUID().toString();
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.AFTER_ROTATION_STORED,
                RefreshReplayConfiguration.Operation.ROTATE, event -> { throw new PlannedFailure(); });
        postRefresh(rollback.refreshToken(), rollbackKey).andExpect(status().isInternalServerError());
        assertThat(refreshRowCount(rollback.userId())).isEqualTo(1);
        assertThat(liveRowCount(rollback.userId())).isEqualTo(1);
        postRefresh(rollback.refreshToken(), rollbackKey).andExpect(status().isOk());
        assertThat(refreshRowCount(rollback.userId())).isEqualTo(2);
        assertThat(liveRowCount(rollback.userId())).isEqualTo(1);

        Registered committed = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552767"));
        String committedKey = UUID.randomUUID().toString();
        AtomicBoolean failOnce = new AtomicBoolean();
        boundaryProbe.armOnce(RefreshReplayConfiguration.Boundary.AFTER_REFRESH_TRANSACTION_COMMIT,
                RefreshReplayConfiguration.Operation.ROTATE, event -> {
                    if (failOnce.compareAndSet(false, true)) {
                        throw new PlannedFailure();
                    }
                });
        postRefresh(committed.refreshToken(), committedKey).andExpect(status().isInternalServerError());
        assertThat(refreshRowCount(committed.userId())).isEqualTo(2);
        MvcResult recovered = postRefresh(committed.refreshToken(), committedKey)
                .andExpect(status().isOk()).andReturn();
        MvcResult repeated = postRefresh(committed.refreshToken(), committedKey)
                .andExpect(status().isOk()).andReturn();
        assertThat(response(recovered).equals(response(repeated))).isTrue();
        assertThat(refreshRowCount(committed.userId())).isEqualTo(2);
        assertThat(liveRowCount(committed.userId())).isEqualTo(1);
    }

    @Test
    void keyedRetriesStillConsumeTheExistingIpRateLimit() throws Exception {
        Registered session = register(ItPhoneRanges.phoneFor(RefreshRotationReplayIT.class, "+84905552768"));
        String key = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 30; attempt++) {
            postRefresh(session.refreshToken(), key, remoteAddr("203.0.113.75")).andExpect(status().isOk());
        }
        postRefresh(session.refreshToken(), key, remoteAddr("203.0.113.75"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(refreshRowCount(session.userId())).isEqualTo(2);
        assertThat(liveRowCount(session.userId())).isEqualTo(1);
    }

    private ResultActions postRefresh(String refreshToken, String key) throws Exception {
        return postRefresh(refreshToken, key, request -> request);
    }

    private ResultActions postRefresh(String refreshToken, String key, RequestPostProcessor postProcessor)
            throws Exception {
        var request = post("/api/v1/auth/refresh").with(postProcessor)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken)));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertUnavailableEncryptionKeyUsesLegacyStrictRotation(String configuredKey, String phone)
            throws Exception {
        replayProperties.setKey(configuredKey);
        Registered session = register(phone);
        String requestKey = UUID.randomUUID().toString();

        postRefresh(session.refreshToken(), requestKey).andExpect(status().isOk());
        Integer replayRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? AND replay_key_digest IS NOT NULL",
                Integer.class, TokenService.sha256Hex(session.refreshToken()));
        assertThat(replayRows).isZero();

        postRefresh(session.refreshToken(), requestKey)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REUSED"));
        assertThat(liveRowCount(session.userId())).isZero();
    }

    private int refreshRowCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    private int liveRowCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
    }

    private static void shutdownWorkers(ExecutorService executor) {
        executor.shutdownNow();
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
                throw new AssertionError("Timed out waiting for the planned refresh lock boundary");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the planned refresh lock boundary", exception);
        }
    }

    private static final class PlannedFailure extends RuntimeException { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReplayTestConfiguration {
        @Bean
        @Primary
        ReplayTestClock replayTestClock() {
            return new ReplayTestClock();
        }

        @Bean
        @Primary
        ReplayBoundaryProbe replayBoundaryProbe() {
            return new ReplayBoundaryProbe();
        }
    }

    static final class ReplayTestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static final class ReplayBoundaryProbe implements RefreshReplayConfiguration.BoundaryHook {
        private final Map<ProbeKey, CopyOnWriteArrayList<Consumer<ProbeEvent>>> handlers = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<ProbeEvent> events = new CopyOnWriteArrayList<>();

        void arm(RefreshReplayConfiguration.Boundary boundary, RefreshReplayConfiguration.Operation operation,
                 Consumer<ProbeEvent> consumer) {
            handlers.computeIfAbsent(new ProbeKey(boundary, operation), ignored -> new CopyOnWriteArrayList<>())
                    .add(consumer);
        }

        void armOnce(RefreshReplayConfiguration.Boundary boundary,
                     RefreshReplayConfiguration.Operation operation, Consumer<ProbeEvent> consumer) {
            AtomicBoolean claimed = new AtomicBoolean();
            arm(boundary, operation, event -> {
                if (claimed.compareAndSet(false, true)) {
                    consumer.accept(event);
                }
            });
        }

        void clear() {
            handlers.clear();
            events.clear();
        }

        List<ProbeEvent> events() {
            return new ArrayList<>(events);
        }

        @Override
        public void reached(RefreshReplayConfiguration.Boundary boundary,
                            RefreshReplayConfiguration.Operation operation, UUID userId, UUID tokenId) {
            ProbeEvent event = new ProbeEvent(boundary, operation, userId, tokenId);
            events.add(event);
            for (Consumer<ProbeEvent> handler : handlers.getOrDefault(
                    new ProbeKey(boundary, operation), new CopyOnWriteArrayList<>())) {
                handler.accept(event);
            }
        }
    }

    record ProbeKey(RefreshReplayConfiguration.Boundary boundary,
                    RefreshReplayConfiguration.Operation operation) { }

    record ProbeEvent(RefreshReplayConfiguration.Boundary boundary,
                     RefreshReplayConfiguration.Operation operation, UUID userId, UUID tokenId) { }
}

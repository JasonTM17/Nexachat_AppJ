package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.auth.FirebaseBridgeLock;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.projection.FirebaseAuthorizationProjectionListener;
import com.nexachat.firebase.projection.FirebaseAuthorizationBackfillJob;
import com.nexachat.firebase.projection.FirebaseAuthorizationProjectionService;
import com.nexachat.firebase.projection.FirebaseProjectionMetrics;
import com.nexachat.firebase.projection.FirebaseProjectionRetryQueue;
import com.nexachat.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises deletion's AFTER_COMMIT Firebase cleanup with an in-memory RTDB client. */
@Import(AccountDeletionFirebaseProjectionIT.FirebaseProjectionTestConfiguration.class)
class AccountDeletionFirebaseProjectionIT extends SecurityITBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InMemoryFirebaseRtdbClient firebase;

    @Autowired
    private FirebaseAuthorizationBackfillJob backfillJob;

    @Test
    void deletedAccountLosesBridgeAccessAndEveryCapturedFirebaseMirror() throws Exception {
        firebase.seed("control/enabled", true);
        Registered deleted = registerUser("+84905553111", "secret123");
        Registered peer = registerUser("+84905553112", "secret123");
        UUID conversationId = openDirect(deleted, peer);

        UUID bridgeId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        long nowMillis = System.currentTimeMillis();
        firebase.seed("sessions/" + deleted.userId() + "/" + bridgeId + "/until", nowMillis + 60_000);
        firebase.seed("presence/" + deleted.userId() + "/" + bridgeId, Map.of("online", true));
        firebase.seed("members/" + conversationId + "/" + deleted.userId(), nowMillis + 60_000);
        firebase.seed("typing/" + conversationId + "/" + deleted.userId(), Map.of("bridgeId", bridgeId.toString()));
        firebase.seed("calls/" + callId, Map.of("conversationId", conversationId.toString(), "state", "ENDED"));

        Instant startedAt = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO call_records(
                    call_id, conversation_id, caller_id, callee_id, state, end_reason,
                    started_at, ended_at, duration_seconds)
                VALUES (?, ?, ?, ?, 'ENDED', 'COMPLETED', ?, ?, 5)
                """, callId, conversationId, deleted.userId(), peer.userId(),
                Timestamp.from(startedAt), Timestamp.from(startedAt.plusSeconds(5)));

        assertThat(firebase.get("accounts/" + deleted.userId()))
                .isEqualTo(Map.of("active", true, "showPresence", true));
        assertThat(firebase.bridgeCanAccess(deleted.userId(), bridgeId, nowMillis)).isTrue();
        assertThat(firebase.get("members/" + conversationId + "/" + deleted.userId())).isNotNull();
        assertThat(firebase.get("presenceReaders/" + peer.userId() + "/" + deleted.userId()))
                .isEqualTo(conversationId.toString());

        deleteMe(deleted.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(firebase.bridgeCanAccess(deleted.userId(), bridgeId, nowMillis)).isFalse();
        assertThat(firebase.get("accounts/" + deleted.userId())).isNull();
        assertThat(firebase.get("sessions/" + deleted.userId() + "/" + bridgeId + "/until")).isNull();
        assertThat(firebase.get("presence/" + deleted.userId() + "/" + bridgeId)).isNull();
        assertThat(firebase.get("members/" + conversationId + "/" + deleted.userId())).isNull();
        assertThat(firebase.get("typing/" + conversationId + "/" + deleted.userId())).isNull();
        assertThat(firebase.get("presenceReaders/" + deleted.userId() + "/" + peer.userId())).isNull();
        assertThat(firebase.get("presenceReaders/" + peer.userId() + "/" + deleted.userId())).isNull();
        assertThat(firebase.get("calls/" + callId)).isNull();

        // Recreate the stale value that a delayed account-created retry or backfill might observe.
        firebase.seed("accounts/" + deleted.userId(), Map.of("active", true, "showPresence", true));
        backfillJob.backfill();

        assertThat(firebase.get("accounts/" + deleted.userId())).isNull();
        assertThat(firebase.get("presenceReaders/" + deleted.userId() + "/" + peer.userId())).isNull();
        assertThat(firebase.get("presenceReaders/" + peer.userId() + "/" + deleted.userId())).isNull();
    }

    private Registered registerUser(String phone, String password) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", password, "displayName", "Firebase Delete"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registered(UUID.fromString(body.path("user").path("id").asText()),
                body.path("accessToken").asText());
    }

    private UUID openDirect(Registered owner, Registered peer) throws Exception {
        var result = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", peer.userId().toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private ResultActions deleteMe(String accessToken, String password) throws Exception {
        return mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", password))));
    }

    private record Registered(UUID userId, String accessToken) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FirebaseProjectionTestConfiguration {

        @Bean
        InMemoryFirebaseRtdbClient inMemoryFirebaseRtdbClient() {
            return new InMemoryFirebaseRtdbClient();
        }

        @Bean
        FirebaseBridgeLock firebaseBridgeLock() {
            return new FirebaseBridgeLock();
        }

        @Bean
        FirebaseProjectionMetrics firebaseProjectionMetrics(MeterRegistry registry) {
            return new FirebaseProjectionMetrics(registry);
        }

        @Bean
        FirebaseAuthorizationProjectionService firebaseAuthorizationProjectionService(
                UserRepository users,
                ConversationRepository conversations,
                ConversationMemberRepository members,
                InMemoryFirebaseRtdbClient firebase,
                FirebaseBridgeLock locks,
                FirebaseProjectionMetrics metrics) {
            return new FirebaseAuthorizationProjectionService(users, conversations, members, firebase, locks, metrics);
        }

        @Bean
        FirebaseProjectionRetryQueue firebaseProjectionRetryQueue(FirebaseProjectionMetrics metrics) {
            return new FirebaseProjectionRetryQueue(Clock.systemUTC(), metrics);
        }

        @Bean
        FirebaseAuthorizationProjectionListener firebaseAuthorizationProjectionListener(
                FirebaseAuthorizationProjectionService projections,
                FirebaseProjectionRetryQueue retries) {
            return new FirebaseAuthorizationProjectionListener(projections, retries);
        }

        @Bean
        FirebaseAuthorizationBackfillJob firebaseAuthorizationBackfillJob(
                UserRepository users,
                ConversationRepository conversations,
                FirebaseAuthorizationProjectionService projections,
                InMemoryFirebaseRtdbClient firebase,
                FirebaseProjectionMetrics metrics) {
            return new FirebaseAuthorizationBackfillJob(users, conversations, projections, firebase, metrics);
        }
    }

    static final class InMemoryFirebaseRtdbClient implements FirebaseRtdbClient {

        private final Map<String, Object> values = new HashMap<>();

        synchronized void seed(String path, Object value) {
            values.put(path, value);
        }

        synchronized Object get(String path) {
            return values.get(path);
        }

        synchronized boolean bridgeCanAccess(UUID userId, UUID bridgeId, long nowMillis) {
            Object accountValue = values.get("accounts/" + userId);
            Object leaseValue = values.get("sessions/" + userId + "/" + bridgeId + "/until");
            if (!(accountValue instanceof Map<?, ?> account) || !(leaseValue instanceof Number leaseUntil)) {
                return false;
            }
            return Boolean.TRUE.equals(values.get("control/enabled"))
                    && Boolean.TRUE.equals(account.get("active"))
                    && leaseUntil.longValue() > nowMillis;
        }

        @Override
        public synchronized void applyAuthorizationProjection(Map<String, Object> updates) {
            updates.forEach((path, value) -> {
                if (value == null) {
                    values.keySet().removeIf(key -> key.equals(path) || key.startsWith(path + "/"));
                } else {
                    values.put(path, value);
                }
            });
        }

        @Override
        public synchronized Long readSessionLease(UUID userId, UUID bridgeId) {
            Object value = values.get("sessions/" + userId + "/" + bridgeId + "/until");
            return value instanceof Number leaseUntil ? leaseUntil.longValue() : null;
        }

        @Override
        public void writeSessionLease(UUID userId, UUID bridgeId, long leaseUntilMillis,
                                      Map<UUID, Long> conversationLeaseUntilMillis) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("sessions/" + userId + "/" + bridgeId + "/until", leaseUntilMillis);
            conversationLeaseUntilMillis.forEach((conversationId, until) ->
                    updates.put("members/" + conversationId + "/" + userId, until));
            applyAuthorizationProjection(updates);
        }

        @Override
        public void deleteBridge(UUID userId, UUID bridgeId) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("sessions/" + userId + "/" + bridgeId, null);
            updates.put("presence/" + userId + "/" + bridgeId, null);
            applyAuthorizationProjection(updates);
        }

        @Override
        public void revokeUser(UUID userId, List<UUID> conversationIds) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("sessions/" + userId, null);
            updates.put("presence/" + userId, null);
            conversationIds.forEach(conversationId -> updates.put("members/" + conversationId + "/" + userId, null));
            applyAuthorizationProjection(updates);
        }
    }
}

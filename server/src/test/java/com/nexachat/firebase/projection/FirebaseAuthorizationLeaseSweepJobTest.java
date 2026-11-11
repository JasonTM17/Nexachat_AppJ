package com.nexachat.firebase.projection;

import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirebaseAuthorizationLeaseSweepJobTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONVERSATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void sweepsExpiredRowsUsingCurrentTimeAndBoundedPaths() {
        UserRepository users = mock(UserRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        RecordingClient client = new RecordingClient();
        when(conversations.findIdsAfter(null, PageRequest.of(0, FirebaseAuthorizationLeaseSweepJob.PAGE_SIZE)))
                .thenReturn(List.of(CONVERSATION_ID));
        when(users.findIdsAfter(null, PageRequest.of(0, FirebaseAuthorizationLeaseSweepJob.PAGE_SIZE)))
                .thenReturn(List.of(USER_ID));
        var job = new FirebaseAuthorizationLeaseSweepJob(users, conversations, client,
                Clock.fixed(NOW, ZoneOffset.UTC), new FirebaseProjectionMetrics(new SimpleMeterRegistry()));

        job.sweepOneBatch();

        assertThat(client.sweeps).containsExactly(
                new Sweep("members/" + CONVERSATION_ID, "$value", NOW.toEpochMilli(), 100),
                new Sweep("typing/" + CONVERSATION_ID, "expiresAt", NOW.toEpochMilli(), 100),
                new Sweep("sessions/" + USER_ID, "until", NOW.toEpochMilli(), 100),
                new Sweep("presence/" + USER_ID, "leaseUntil", NOW.toEpochMilli(), 100));
    }

    private record Sweep(String path, String field, long cutoff, int limit) {
    }

    private static final class RecordingClient implements FirebaseRtdbClient {
        private final List<Sweep> sweeps = new ArrayList<>();

        @Override
        public void applyAuthorizationProjection(java.util.Map<String, Object> updates) {
        }

        @Override
        public int deleteExpiredChildren(String parentPath, String orderBy, long cutoffMillis, int limit) {
            sweeps.add(new Sweep(parentPath, orderBy, cutoffMillis, limit));
            return 1;
        }

        @Override
        public int expirePresenceEntries(UUID userId, long cutoffMillis, int limit) {
            sweeps.add(new Sweep("presence/" + userId, "leaseUntil", cutoffMillis, limit));
            return 1;
        }

        @Override
        public Long readSessionLease(UUID userId, UUID bridgeId) {
            return null;
        }

        @Override
        public void writeSessionLease(UUID userId, UUID bridgeId, long leaseUntilMillis,
                                      java.util.Map<UUID, Long> conversationLeaseUntilMillis) {
        }

        @Override
        public void deleteBridge(UUID userId, UUID bridgeId) {
        }

        @Override
        public void revokeUser(UUID userId, List<UUID> conversationIds) {
        }
    }
}

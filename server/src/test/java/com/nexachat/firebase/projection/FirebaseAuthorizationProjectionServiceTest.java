package com.nexachat.firebase.projection;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.auth.FirebaseBridgeLock;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.user.User;
import com.nexachat.user.UserAccountDeletedEvent;
import com.nexachat.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirebaseAuthorizationProjectionServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PEER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CONVERSATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID CALL_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final UserRepository users = mock(UserRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
    private final FakeRtdbClient rtdb = new FakeRtdbClient();
    private FirebaseAuthorizationProjectionService service;

    @BeforeEach
    void setUp() {
        service = new FirebaseAuthorizationProjectionService(
                users, conversations, members, rtdb, new FirebaseBridgeLock(),
                new FirebaseProjectionMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void directReaderEdgesAreBuiltFromCurrentMembersAndRepeatedWritesAreIdempotent() {
        Conversation direct = mock(Conversation.class);
        when(conversations.findByIdForUpdate(CONVERSATION_ID)).thenReturn(Optional.of(direct));
        when(conversations.findById(CONVERSATION_ID)).thenReturn(Optional.of(direct));
        when(direct.getType()).thenReturn(Conversation.Type.DIRECT);
        when(members.findMemberUserIds(CONVERSATION_ID)).thenReturn(List.of(PEER_ID, USER_ID));
        when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(mock(User.class)));
        when(users.findByIdForUpdate(PEER_ID)).thenReturn(Optional.of(mock(User.class)));

        service.reconcileConversation(CONVERSATION_ID);
        service.reconcileConversation(CONVERSATION_ID);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("presenceReaders/" + USER_ID + "/" + PEER_ID, CONVERSATION_ID.toString());
        expected.put("presenceReaders/" + PEER_ID + "/" + USER_ID, CONVERSATION_ID.toString());
        assertThat(rtdb.patches).containsExactly(expected, expected);
    }

    @Test
    void groupConversationNeverCreatesDirectPresenceReaderEdges() {
        Conversation group = mock(Conversation.class);
        when(conversations.findByIdForUpdate(CONVERSATION_ID)).thenReturn(Optional.of(group));
        when(group.getType()).thenReturn(Conversation.Type.GROUP);

        service.reconcileConversation(CONVERSATION_ID);

        assertThat(rtdb.patches).isEmpty();
    }

    @Test
    void privacyMirrorUsesTheLatestDurableSetting() {
        User user = mock(User.class);
        when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(user.isShowPresence()).thenReturn(false);

        service.refreshAccount(USER_ID);

        assertThat(rtdb.patches).containsExactly(Map.of(
                "accounts/" + USER_ID, Map.of("active", true, "showPresence", false)));
    }

    @Test
    void removedMemberInvalidatesMembershipLeaseAndTypingLeafTogether() {
        when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        service.revokeMember(CONVERSATION_ID, USER_ID);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("members/" + CONVERSATION_ID + "/" + USER_ID, null);
        expected.put("typing/" + CONVERSATION_ID + "/" + USER_ID, null);
        assertThat(rtdb.patches).containsExactly(expected);
    }

    @Test
    void deletionHookPurgesAccountAndEveryCapturedAuthorizationEdgeAtomically() {
        UserAccountDeletedEvent event = new UserAccountDeletedEvent(
                USER_ID, List.of(CONVERSATION_ID), List.of(PEER_ID), List.of(PEER_ID), List.of(CALL_ID));

        service.deleteAccount(event);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("accounts/" + USER_ID, null);
        expected.put("sessions/" + USER_ID, null);
        expected.put("presence/" + USER_ID, null);
        expected.put("presenceReaders/" + USER_ID, null);
        expected.put("blocked/" + USER_ID, null);
        expected.put("members/" + CONVERSATION_ID + "/" + USER_ID, null);
        expected.put("typing/" + CONVERSATION_ID + "/" + USER_ID, null);
        expected.put("presenceReaders/" + PEER_ID + "/" + USER_ID, null);
        expected.put("blocked/" + PEER_ID + "/" + USER_ID, null);
        expected.put("calls/" + CALL_ID, null);
        assertThat(rtdb.patches).containsExactly(expected);
    }

    private static final class FakeRtdbClient implements FirebaseRtdbClient {
        private final List<Map<String, Object>> patches = new ArrayList<>();

        @Override
        public void applyAuthorizationProjection(Map<String, Object> updates) {
            patches.add(new LinkedHashMap<>(updates));
        }

        @Override
        public int deleteExpiredChildren(String parentPath, String orderBy, long cutoffMillis, int limit) {
            return 0;
        }

        @Override
        public Long readSessionLease(UUID userId, UUID bridgeId) {
            return null;
        }

        @Override
        public void writeSessionLease(UUID userId, UUID bridgeId, long leaseUntilMillis,
                                      Map<UUID, Long> conversationLeaseUntilMillis) {
        }

        @Override
        public void deleteBridge(UUID userId, UUID bridgeId) {
        }

        @Override
        public void revokeUser(UUID userId, List<UUID> conversationIds) {
        }
    }
}

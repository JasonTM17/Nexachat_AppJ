package com.nexachat.firebase.auth;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.projection.FirebaseAuthorizationProjectionService;
import com.nexachat.firebase.projection.FirebaseProjectionMetrics;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves that multi-user projection work takes Firebase bridge locks in stripe
 * order, not user-id order, and that a shared stripe is only acquired once.
 */
class FirebaseBridgeLockOrderTest {

    // Inverted pairs: id order is A < B but stripe order is B(5) < A(9);
    // C < D but stripe order is C(5) < D(9). The old user-id ordering therefore
    // acquired 9 then 5 in one call and 5 then 9 in the other.
    private static final UUID INVERTED_FIRST = UUID.fromString("11111111-1111-1111-0123-456789abcd6e");
    private static final UUID INVERTED_SECOND = UUID.fromString("22222222-2222-2222-0123-456789abcd62");
    private static final UUID REVERSE_FIRST = UUID.fromString("33333333-3333-3333-0123-456789abcd62");
    private static final UUID REVERSE_SECOND = UUID.fromString("44444444-4444-4444-0123-456789abcd6e");

    // Colliding pair: different ids, same stripe 13.
    private static final UUID COLLIDING_FIRST = UUID.fromString("55555555-5555-5555-0123-456789abcd6a");
    private static final UUID COLLIDING_SECOND = UUID.fromString("66666666-6666-6666-0123-456789abcd6a");

    private static final UUID INVERTED_CONVERSATION = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID REVERSE_CONVERSATION = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID COLLIDING_CONVERSATION = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    private final UserRepository users = mock(UserRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
    private final FirebaseRtdbClient rtdb = mock(FirebaseRtdbClient.class);
    private final List<Integer> acquiredStripes = new CopyOnWriteArrayList<>();
    private FirebaseBridgeLock locks;
    private FirebaseAuthorizationProjectionService service;

    @BeforeEach
    void setUp() {
        locks = new FirebaseBridgeLock(acquiredStripes::add);
        service = new FirebaseAuthorizationProjectionService(
                users, conversations, members, rtdb, locks,
                new FirebaseProjectionMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void multiUserLocksAreAcquiredInAscendingStripeOrderRegardlessOfUserIdOrder() {
        assertThat(FirebaseBridgeLock.stripeIndex(INVERTED_FIRST)).isEqualTo(9);
        assertThat(FirebaseBridgeLock.stripeIndex(INVERTED_SECOND)).isEqualTo(5);
        assertThat(FirebaseBridgeLock.stripeIndex(REVERSE_FIRST)).isEqualTo(5);
        assertThat(FirebaseBridgeLock.stripeIndex(REVERSE_SECOND)).isEqualTo(9);
        assertThat(INVERTED_FIRST).isLessThan(INVERTED_SECOND);
        assertThat(REVERSE_FIRST).isLessThan(REVERSE_SECOND);

        stubDirectConversation(INVERTED_CONVERSATION, INVERTED_FIRST, INVERTED_SECOND);
        stubDirectConversation(REVERSE_CONVERSATION, REVERSE_FIRST, REVERSE_SECOND);
        stubActiveUser(INVERTED_FIRST);
        stubActiveUser(INVERTED_SECOND);
        stubActiveUser(REVERSE_FIRST);
        stubActiveUser(REVERSE_SECOND);

        service.reconcileConversation(INVERTED_CONVERSATION);
        assertThat(acquiredStripes).containsExactly(5, 9);

        acquiredStripes.clear();
        service.reconcileConversation(REVERSE_CONVERSATION);
        assertThat(acquiredStripes).containsExactly(5, 9);
    }

    @Test
    void membersThatShareAStripeAcquireItOnlyOnce() {
        assertThat(FirebaseBridgeLock.stripeIndex(COLLIDING_FIRST)).isEqualTo(13);
        assertThat(FirebaseBridgeLock.stripeIndex(COLLIDING_SECOND)).isEqualTo(13);

        stubDirectConversation(COLLIDING_CONVERSATION, COLLIDING_FIRST, COLLIDING_SECOND);
        stubActiveUser(COLLIDING_FIRST);
        stubActiveUser(COLLIDING_SECOND);

        service.reconcileConversation(COLLIDING_CONVERSATION);

        assertThat(acquiredStripes).containsExactly(13);
        assertThat(locks.lockFor(COLLIDING_FIRST).isLocked()).isFalse();
    }

    private void stubDirectConversation(UUID conversationId, UUID... userIds) {
        Conversation conversation = mock(Conversation.class);
        when(conversation.getType()).thenReturn(Conversation.Type.DIRECT);
        when(conversations.findByIdForUpdate(conversationId)).thenReturn(Optional.of(conversation));
        when(conversations.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(members.findMemberUserIds(conversationId))
                .thenReturn(Arrays.stream(userIds).toList());
    }

    private void stubActiveUser(UUID userId) {
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(User.class)));
    }
}

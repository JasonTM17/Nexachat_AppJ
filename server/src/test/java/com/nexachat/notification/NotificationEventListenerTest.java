package com.nexachat.notification;

import com.nexachat.call.CallMediaType;
import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallRingingEvent;
import com.nexachat.call.CallSession;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.dto.PushRecipientRow;
import com.nexachat.message.Message;
import com.nexachat.message.MessageCreatedEvent;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private FcmPushService fcmPushService;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private com.nexachat.message.MessageRepository messageRepository;
    @Mock
    private com.nexachat.user.UserBlockRepository blockRepository;
    @Mock
    private PresenceSessionState presenceState;
    @Mock
    private CallRegistry callRegistry;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDeviceTokenRepository tokenRepository;

    private NotificationEventListener listener;

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(
                fcmPushService, memberRepository, messageRepository, blockRepository,
                presenceState, callRegistry, userRepository,
                tokenRepository, Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC));
        // The listener batches token reads through findByUserIdIn; route the
        // batch call through the per-user stubs each test sets up.
        lenient().when(tokenRepository.findByUserIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(0);
            List<UserDeviceToken> rows = new java.util.ArrayList<>();
            for (UUID id : ids) {
                List<UserDeviceToken> found = tokenRepository.findByUserId(id);
                if (found != null) {
                    rows.addAll(found);
                }
            }
            return rows;
        });
    }

    @Test
    void replayedSendDoesNotDispatchPushOrReadRecipients() {
        listener.onMessageCreated(new MessageCreatedEvent(
                conversationId, message(Message.Type.TEXT, 7L), null, true));

        verifyNoInteractions(fcmPushService, memberRepository, presenceState, tokenRepository);
    }

    @Test
    void createdSendDispatchesOnePushForOfflineRecipient() {
        MessageResponse message = message(Message.Type.TEXT, 8L);
        stubOfflineRecipient();

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message));

        assertSinglePush(message, false);
    }

    @Test
    void createdSystemEventStillDispatchesPushToRemovedSubject() {
        MessageResponse message = message(Message.Type.SYSTEM, 9L);
        // The removed subject is no longer in the member list at fan-out time.
        when(memberRepository.findPushRecipients(eq(conversationId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(new PushRecipientRow(senderId, false)));
        when(presenceState.isOnline(recipientId)).thenReturn(false);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(recipientId);
        UserDeviceToken token = UserDeviceToken.create(
                owner, "subject-token", UserDeviceToken.Platform.ANDROID, "Phone");
        when(tokenRepository.findByUserId(recipientId)).thenReturn(List.of(token));

        listener.onMessageCreated(new MessageCreatedEvent(
                conversationId, message, recipientId));

        assertSinglePush(message, false);
    }

    private void stubOfflineRecipient() {
        when(memberRepository.findPushRecipients(eq(conversationId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(new PushRecipientRow(senderId, false),
                        new PushRecipientRow(recipientId, false)));
        when(presenceState.isOnline(recipientId)).thenReturn(false);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(recipientId);
        UserDeviceToken token = UserDeviceToken.create(
                owner, "recipient-token", UserDeviceToken.Platform.ANDROID, "Phone");
        when(tokenRepository.findByUserId(recipientId)).thenReturn(List.of(token));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedDeviceTokenGetsOnePushForTheAccountSignedInLast() {
        UUID secondRecipientId = UUID.randomUUID();
        MessageResponse message = message(Message.Type.TEXT, 10L);
        when(memberRepository.findPushRecipients(eq(conversationId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(new PushRecipientRow(senderId, false),
                        new PushRecipientRow(recipientId, false),
                        new PushRecipientRow(secondRecipientId, false)));
        when(presenceState.isOnline(recipientId)).thenReturn(false);
        when(presenceState.isOnline(secondRecipientId)).thenReturn(false);
        UserDeviceToken older = sharedRow(Instant.parse("2026-11-10T08:00:00Z"), recipientId);
        UserDeviceToken newer = sharedRow(Instant.parse("2026-11-11T08:00:00Z"), secondRecipientId);
        when(tokenRepository.findByUserId(recipientId)).thenReturn(List.of(older));
        when(tokenRepository.findByUserId(secondRecipientId)).thenReturn(List.of(newer));

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message));

        ArgumentCaptor<List<UserDeviceToken>> tokens = ArgumentCaptor.forClass(List.class);
        verify(fcmPushService).sendNewMessageNotification(eq(secondRecipientId), tokens.capture(),
                eq(conversationId), eq(message.id()), eq(message.seq()),
                eq(message.senderDisplayName()), eq(false));
        assertThat(tokens.getValue()).containsExactly(newer);
        verify(fcmPushService, never()).sendNewMessageNotification(eq(recipientId), any(List.class),
                any(UUID.class), any(UUID.class), anyLong(), any(), anyBoolean());
    }

    @Test
    void recipientsAreFilteredByAdmissionAtTheMessageCreationTime() {
        MessageResponse message = message(Message.Type.TEXT, 11L);
        stubOfflineRecipient();

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message));

        verify(memberRepository).findPushRecipients(
                eq(conversationId), eq(message.createdAt()), any(Instant.class));
    }

    @Test
    void createdSendWithFanOutSnapshotRechecksEligibilityInOneQuery() {
        MessageResponse message = message(Message.Type.TEXT, 12L);
        stubOfflineRecipient();

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message, null, false,
                List.of(senderId, recipientId), null));

        assertSinglePush(message, false);
        verify(memberRepository).findPushRecipients(
                eq(conversationId), eq(message.createdAt()), any(Instant.class));
        verify(memberRepository, never()).findAdmittedUserIds(any(), any());
        verify(messageRepository, never()).findPeerSuppressedSenderId(any());
    }

    /** The snapshot carries the suppressed sender id: no push is dispatched at all. */
    @Test
    void snapshotPeerSuppressedSendSkipsPushWithoutRequerying() {
        MessageResponse message = message(Message.Type.TEXT, 13L);

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message, null, false,
                List.of(senderId, recipientId), senderId));

        verifyNoInteractions(fcmPushService, memberRepository, presenceState, tokenRepository);
        verify(messageRepository, never()).findPeerSuppressedSenderId(any());
    }

    // ------------------------------------------------------------- calls

    @Test
    void callRingingAlwaysSendsTheInviteWithoutConsultingPresence() {
        UUID calleeId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        stubRingingCall(callId, calleeId);

        listener.onCallRinging(new CallRingingEvent(callId));

        verify(fcmPushService).sendCallInviteNotification(eq(calleeId), eq(callId), eq(conversationId),
                eq(callerId), eq("Caller"), eq("AUDIO"),
                eq(Instant.parse("2026-11-11T11:30:00Z").toEpochMilli()));
        verifyNoInteractions(presenceState);
    }

    @Test
    void callRingingSendsTheInviteWhileTheCalleeIsOnlineToo() {
        UUID calleeId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        stubRingingCall(callId, calleeId);
        // The listener must push even when the registry reports the callee
        // online: a socket may be half-open, so presence never gates a ring.
        lenient().when(presenceState.isOnline(calleeId)).thenReturn(true);

        listener.onCallRinging(new CallRingingEvent(callId));

        verify(fcmPushService).sendCallInviteNotification(eq(calleeId), eq(callId), eq(conversationId),
                eq(callerId), eq("Caller"), eq("AUDIO"),
                eq(Instant.parse("2026-11-11T11:30:00Z").toEpochMilli()));
    }

    private void stubRingingCall(UUID callId, UUID calleeId) {
        CallSession ringing = CallSession.ringing(callId, conversationId, callerId, calleeId,
                CallMediaType.AUDIO, Instant.parse("2026-11-11T11:30:00Z"));
        when(callRegistry.find(callId)).thenReturn(Optional.of(ringing));
        User caller = mock(User.class);
        when(caller.getDisplayName()).thenReturn("Caller");
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
    }

    private static UserDeviceToken sharedRow(Instant updatedAt, UUID ownerId) {
        UserDeviceToken row = mock(UserDeviceToken.class);
        when(row.getToken()).thenReturn("shared-device-token");
        when(row.getUpdatedAt()).thenReturn(updatedAt);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        when(row.getUser()).thenReturn(owner);
        return row;
    }

    @SuppressWarnings("unchecked")
    private void assertSinglePush(MessageResponse message, boolean mentioned) {
        ArgumentCaptor<List<UserDeviceToken>> tokens = ArgumentCaptor.forClass(List.class);
        verify(fcmPushService).sendNewMessageNotification(eq(recipientId), tokens.capture(),
                eq(conversationId), eq(message.id()), eq(message.seq()),
                eq(message.senderDisplayName()), eq(mentioned));
        assertThat(tokens.getValue()).hasSize(1);
    }

    private MessageResponse message(Message.Type type, long seq) {
        return new MessageResponse(
                UUID.randomUUID(), conversationId, seq, senderId, "Sender",
                type.name(), "Test message", UUID.randomUUID(), null,
                null, List.of(), List.of(), Instant.now(), null, null);
    }
}

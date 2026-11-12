package com.nexachat.notification;

import com.nexachat.call.CallRegistry;
import com.nexachat.conversation.ConversationMemberRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(
                fcmPushService, memberRepository, presenceState, callRegistry, userRepository,
                tokenRepository, Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC));
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
        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of(senderId));
        when(memberRepository.findMutedMemberIds(eq(conversationId), any(Instant.class)))
                .thenReturn(List.of());
        when(presenceState.isOnline(recipientId)).thenReturn(false);
        User owner = mock(User.class);
        UserDeviceToken token = UserDeviceToken.create(
                owner, "subject-token", UserDeviceToken.Platform.ANDROID, "Phone");
        when(tokenRepository.findByUserId(recipientId)).thenReturn(List.of(token));

        listener.onMessageCreated(new MessageCreatedEvent(
                conversationId, message, recipientId));

        assertSinglePush(message, false);
    }

    private void stubOfflineRecipient() {
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(senderId, recipientId));
        when(memberRepository.findMutedMemberIds(eq(conversationId), any(Instant.class)))
                .thenReturn(List.of());
        when(presenceState.isOnline(recipientId)).thenReturn(false);
        User owner = mock(User.class);
        UserDeviceToken token = UserDeviceToken.create(
                owner, "recipient-token", UserDeviceToken.Platform.ANDROID, "Phone");
        when(tokenRepository.findByUserId(recipientId)).thenReturn(List.of(token));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedDeviceTokenGetsOnePushForTheAccountSignedInLast() {
        UUID secondRecipientId = UUID.randomUUID();
        MessageResponse message = message(Message.Type.TEXT, 10L);
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(senderId, recipientId, secondRecipientId));
        when(memberRepository.findMutedMemberIds(eq(conversationId), any(Instant.class)))
                .thenReturn(List.of());
        when(presenceState.isOnline(recipientId)).thenReturn(false);
        when(presenceState.isOnline(secondRecipientId)).thenReturn(false);
        UserDeviceToken older = sharedRow(Instant.parse("2026-11-10T08:00:00Z"));
        UserDeviceToken newer = sharedRow(Instant.parse("2026-11-11T08:00:00Z"));
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

    private static UserDeviceToken sharedRow(Instant updatedAt) {
        UserDeviceToken row = mock(UserDeviceToken.class);
        when(row.getToken()).thenReturn("shared-device-token");
        when(row.getUpdatedAt()).thenReturn(updatedAt);
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

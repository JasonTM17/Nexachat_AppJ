package com.nexachat.notification;

import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.message.MessageCreatedEvent;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Push recipients must be admitted at creation and still eligible when the
 * AFTER_COMMIT callback runs.
 */
@RecordApplicationEvents
class NotificationAdmissionScopeIT extends ConcurrentDatabaseITSupport {

    @Autowired private MessageService messages;
    @Autowired private NotificationEventListener notifications;
    @Autowired private UserDeviceTokenRepository tokens;
    @Autowired private ApplicationEvents events;
    @MockitoBean private FcmPushService fcmPushService;

    @Test
    @SuppressWarnings("unchecked")
    void memberAdmittedAfterMessageCreationIsNotPushed() {
        var owner = user("Owner");
        var early = user("Early");
        var late = user("Late");
        UUID conversation = group(owner, early);
        MessageResponse created = messages.send(owner.getId(), conversation,
                new SendMessageRequest(UUID.randomUUID(), "TEXT",
                        "committed before the late member joined", null, List.of())).message();

        // Admission commits after the message; replaying the event callback for
        // the stored row is exactly the delayed dispatch the boundary must hold.
        groups.addMembers(owner.getId(), conversation,
                new AddMemberRequest(List.of(late.getId())));
        tokens.save(UserDeviceToken.create(early, "early-token",
                UserDeviceToken.Platform.ANDROID, "phone"));
        tokens.save(UserDeviceToken.create(late, "late-token",
                UserDeviceToken.Platform.ANDROID, "phone"));

        notifications.onMessageCreated(new MessageCreatedEvent(conversation, created));

        verify(fcmPushService).sendNewMessageNotification(eq(early.getId()), any(List.class),
                eq(conversation), eq(created.id()), eq(created.seq()),
                eq(created.senderDisplayName()), eq(false));
        verify(fcmPushService, never()).sendNewMessageNotification(eq(late.getId()),
                any(List.class), any(UUID.class), any(UUID.class), anyLong(), any(), anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchChecksMembershipEvenWhenTheEventCarriesAnAdmissionSnapshot() {
        var owner = user("Owner");
        var retained = user("Retained");
        var removed = user("Removed");
        UUID conversation = group(owner, retained, removed);
        MessageResponse created = messages.send(owner.getId(), conversation,
                new SendMessageRequest(UUID.randomUUID(), "TEXT",
                        "a message for current members", null, List.of())).message();
        MessageCreatedEvent pending = events.stream(MessageCreatedEvent.class)
                .filter(event -> event.message().id().equals(created.id()))
                .findFirst().orElseThrow();

        groups.removeMember(owner.getId(), conversation, removed.getId());
        tokens.save(UserDeviceToken.create(retained, "retained-token",
                UserDeviceToken.Platform.ANDROID, "phone"));
        tokens.save(UserDeviceToken.create(removed, "removed-token",
                UserDeviceToken.Platform.ANDROID, "phone"));

        notifications.onMessageCreated(pending);

        verify(fcmPushService).sendNewMessageNotification(eq(retained.getId()), any(List.class),
                eq(conversation), eq(created.id()), eq(created.seq()),
                eq(created.senderDisplayName()), eq(false));
        verify(fcmPushService, never()).sendNewMessageNotification(eq(removed.getId()),
                any(List.class), any(UUID.class), any(UUID.class), anyLong(), any(), anyBoolean());
    }
}

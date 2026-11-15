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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Push fan-out races group admission: the AFTER_COMMIT callback can run after
 * a member was added, so recipients must be filtered by who was admitted when
 * the message was created — not by who is a member when the listener runs.
 */
class NotificationAdmissionScopeIT extends ConcurrentDatabaseITSupport {

    @Autowired private MessageService messages;
    @Autowired private NotificationEventListener notifications;
    @Autowired private UserDeviceTokenRepository tokens;
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
}

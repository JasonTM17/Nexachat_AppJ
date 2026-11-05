package com.nexachat.presence;

import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.WsEventType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PresenceBroadcastListener {

    private final SimpMessagingTemplate messaging;

    public PresenceBroadcastListener(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPresenceChanged(UserPresenceChangedEvent event) {
        WsEventType eventType = event.online() ? WsEventType.USER_ONLINE : WsEventType.USER_OFFLINE;
        EventEnvelope envelope = EventEnvelope.of(eventType, null,
                new PresencePayload(event.userId(), event.online(), event.lastSeenAt()));
        for (var recipientId : event.recipientIds()) {
            messaging.convertAndSendToUser(recipientId.toString(), "/queue/presence", envelope);
        }
    }
}

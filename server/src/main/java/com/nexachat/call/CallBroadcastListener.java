package com.nexachat.call;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CallBroadcastListener {

    private final SimpMessagingTemplate messaging;

    public CallBroadcastListener(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallDelivery(CallDelivery delivery) {
        messaging.convertAndSendToUser(delivery.recipientId().toString(), "/queue/calls", delivery.event());
    }
}

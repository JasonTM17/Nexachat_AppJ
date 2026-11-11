package com.nexachat.firebase.projection;

import com.nexachat.conversation.ConversationMembershipChangedEvent;
import com.nexachat.firebase.client.FirebaseProviderException;
import com.nexachat.user.UserAccountDeletedEvent;
import com.nexachat.user.UserAccountCreatedEvent;
import com.nexachat.user.UserPresenceVisibilityChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Applies current PostgreSQL authorization state after its transaction commits. */
public class FirebaseAuthorizationProjectionListener {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthorizationProjectionListener.class);

    private final FirebaseAuthorizationProjectionService projections;
    private final FirebaseProjectionRetryQueue retries;

    public FirebaseAuthorizationProjectionListener(FirebaseAuthorizationProjectionService projections,
                                                   FirebaseProjectionRetryQueue retries) {
        this.projections = projections;
        this.retries = retries;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMembershipChanged(ConversationMembershipChangedEvent event) {
        for (var removedUserId : event.removedUserIds()) {
            runOrQueue("member:" + event.conversationId() + ":" + removedUserId,
                    () -> projections.revokeMember(event.conversationId(), removedUserId), true);
        }
        runOrQueue("conversation:" + event.conversationId(),
                () -> projections.reconcileConversation(event.conversationId()), false);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountCreated(UserAccountCreatedEvent event) {
        runOrQueue("account:" + event.userId(),
                () -> projections.refreshAccount(event.userId()), false);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPresenceVisibilityChanged(UserPresenceVisibilityChangedEvent event) {
        runOrQueue("account:" + event.userId(),
                () -> projections.refreshAccount(event.userId()), !event.showPresence());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountDeleted(UserAccountDeletedEvent event) {
        runOrQueue("deleted-account:" + event.userId(),
                () -> projections.deleteAccount(event), true);
    }

    private void runOrQueue(String key, Runnable operation, boolean denyMustSucceed) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            boolean queued = retries.enqueue(key, operation);
            if (queued) {
                log.warn("Firebase authorization projection deferred ({})",
                        failure.getClass().getSimpleName());
            } else {
                log.error("Firebase authorization projection retry was rejected ({})",
                        failure.getClass().getSimpleName());
            }
            if (denyMustSucceed) {
                throw new FirebaseProviderException(queued
                        ? "Firebase authorization deny update did not complete"
                        : "Firebase authorization deny update could not be queued");
            }
        }
    }
}

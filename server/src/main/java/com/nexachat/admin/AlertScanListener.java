package com.nexachat.admin;

import com.nexachat.message.MessageCreatedEvent;
import com.nexachat.message.MessageDeletedEvent;
import com.nexachat.message.MessageUpdatedEvent;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.summary.ConversationAiOptOutEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns durable message lifecycle events into scan jobs and privacy actions.
 *
 * <p>All handlers run {@code AFTER_COMMIT}, the same phase the broadcast
 * listener uses, so a scan never touches a message row that could still roll
 * back. The hard privacy boundary lives here: a message whose wire shape
 * carries an {@code encryption} marker is E2EE — its content is ciphertext,
 * and the server must not analyze it, so it is dropped before ever reaching
 * the queue.
 */
@Component
public class AlertScanListener {

    private static final String TEXT_TYPE = "TEXT";

    private final AlertScanQueue queue;
    private final AlertRepository alertRepository;

    public AlertScanListener(AlertScanQueue queue, AlertRepository alertRepository) {
        this.queue = queue;
        this.alertRepository = alertRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(MessageCreatedEvent event) {
        MessageResponse message = event.message();
        if (eligible(message)) {
            queue.enqueue(new AlertScanQueue.ScanTask(
                    message.id(), event.conversationId(), message.senderId(),
                    seq(message), message.content(), message.createdAt(), false));
        }
    }

    /** Edited content is re-scanned; the alert row refreshes or re-opens. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageUpdated(MessageUpdatedEvent event) {
        MessageResponse message = event.message();
        if (eligible(message)) {
            queue.enqueue(new AlertScanQueue.ScanTask(
                    message.id(), event.conversationId(), message.senderId(),
                    seq(message), message.content(), message.createdAt(), true));
        }
    }

    /** Recalled messages keep the audit row but lose their stored snippet. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageDeleted(MessageDeletedEvent event) {
        alertRepository.tombstoneSnippetForMessage(event.payload().messageId());
    }

    /** Post-hoc opt-out: tombstone the member's stored snippets in this conversation. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationOptOut(ConversationAiOptOutEvent event) {
        alertRepository.tombstoneSnippetsForSender(event.conversationId(), event.userId());
    }

    /**
     * Scan eligibility: plain {@code TEXT} content only. E2EE ciphertext
     * ({@code encryption != null}) is a hard exclusion — scanning it is both
     * useless (ciphertext matches nothing) and a privacy boundary violation.
     */
    private boolean eligible(MessageResponse message) {
        return message != null
                && TEXT_TYPE.equals(message.type())
                && message.content() != null
                && !message.content().isBlank()
                && message.encryption() == null
                && message.deletedAt() == null;
    }

    private static long seq(MessageResponse message) {
        return message.seq() == null ? 0 : message.seq();
    }
}

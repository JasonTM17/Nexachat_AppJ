package com.nexachat.summary;

import com.nexachat.message.MessageDeletedEvent;
import com.nexachat.message.MessageUpdatedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Removes cached summaries whose sequence window includes a recalled or edited message.
 */
@Component
@Profile("!test")
public class MessageSummaryCacheInvalidationListener {

    private final SummaryCacheRepository summaryCacheRepository;

    public MessageSummaryCacheInvalidationListener(SummaryCacheRepository summaryCacheRepository) {
        this.summaryCacheRepository = summaryCacheRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageRecalled(MessageDeletedEvent event) {
        summaryCacheRepository.deleteContainingMessage(event.conversationId(), event.payload().messageId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageEdited(MessageUpdatedEvent event) {
        Long sequence = event.message().seq();
        if (sequence != null) {
            summaryCacheRepository.deleteContainingSequence(event.conversationId(), sequence);
        }
    }
}

package com.nexachat.translate;

import com.nexachat.message.MessageDeletedEvent;
import com.nexachat.message.MessageUpdatedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops cached translations when the source message changes. Runs
 * BEFORE_COMMIT so a recall never leaves its translated text behind, and an
 * edit clears rows even though its new change_version would have made them
 * unreachable anyway.
 */
@Component
@Profile("!test")
public class MessageTranslationCacheInvalidationListener {

    private final TranslationCacheRepository cacheRepository;

    public MessageTranslationCacheInvalidationListener(TranslationCacheRepository cacheRepository) {
        this.cacheRepository = cacheRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageRecalled(MessageDeletedEvent event) {
        cacheRepository.deleteByMessageId(event.payload().messageId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageEdited(MessageUpdatedEvent event) {
        if (event.message() != null && event.message().id() != null) {
            cacheRepository.deleteByMessageId(event.message().id());
        }
    }
}

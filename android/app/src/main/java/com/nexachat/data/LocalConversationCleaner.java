package com.nexachat.data;

import com.nexachat.core.database.AppDatabase;

/**
 * Deletes every local row that belongs to one conversation after the server
 * proves the caller can no longer access it. Callers run this inside
 * {@link AppDatabase#runInTransaction(Runnable)} so the inbox row, cached
 * history, reactions, pins, and queued sends disappear together.
 */
final class LocalConversationCleaner {

    private LocalConversationCleaner() {
    }

    static void deleteInTransaction(
            AppDatabase database, String conversationId, String ownerUserId) {
        database.pinDao().deleteForConversation(conversationId).blockingAwait();
        database.outboxDao().deleteForConversation(conversationId, ownerUserId).blockingAwait();
        database.reactionDao().deleteForConversation(conversationId).blockingAwait();
        database.messageDao().deleteForConversation(conversationId).blockingAwait();
        database.conversationDao().deleteById(conversationId).blockingAwait();
    }
}

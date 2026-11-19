package com.nexachat.data;

import com.nexachat.core.database.AppDatabase;

/**
 * Deletes every local row that belongs to one conversation after the server
 * proves the caller can no longer access it. Callers run this inside
 * {@link AppDatabase#runInTransaction(Runnable)} so the inbox row, cached
 * history, reactions, pins, and queued sends disappear together.
 *
 * <p>Only synchronous {@code *InTransaction} DAO calls are allowed inside the
 * held writer transaction (issue #184). {@code blockingAwait()} on a
 * Completable parks the caller on a latch while the work it awaits is queued
 * behind the very writer connection the transaction holds — under accumulated
 * sync load that produces the 60s+ connection-pool waits that froze the app.
 * Every delete below is a plain statement on the transaction's own session.
 */
final class LocalConversationCleaner {

    private LocalConversationCleaner() {
    }

    static void deleteInTransaction(
            AppDatabase database, String conversationId, String ownerUserId) {
        if (database.syncCursorDao() != null) database.syncCursorDao().delete(ownerUserId, conversationId);
        database.pinDao().deleteForConversationInTransaction(conversationId);
        database.outboxDao().deleteForConversationInTransaction(conversationId, ownerUserId);
        database.reactionDao().deleteForConversationInTransaction(conversationId);
        database.messageDao().deleteForConversationInTransaction(conversationId);
        database.messageTranslationDao().deleteForConversationInTransaction(conversationId);
        database.conversationDao().deleteByIdInTransaction(conversationId);
    }
}

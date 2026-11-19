package com.nexachat.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageTranslationDao;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.database.SyncCursorDao;
import io.reactivex.rxjava3.core.Completable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;

/**
 * Structural guard for issue #184: inside {@link AppDatabase#runInTransaction}
 * every delete must be a synchronous {@code *InTransaction} DAO call. The
 * previous shape parked the caller on {@code Completable#blockingAwait()},
 * turning each delete into hold-and-wait pressure on the single SQLCipher
 * writer connection. The async DAO variants are stubbed to fail loudly, so a
 * regression that reintroduces blockingAwait inside the transaction dies here.
 */
public final class LocalConversationCleanerTest {

    private final AppDatabase appDatabase = org.mockito.Mockito.mock(AppDatabase.class);
    private final SyncCursorDao syncCursorDao = org.mockito.Mockito.mock(SyncCursorDao.class);
    private final PinDao pinDao = org.mockito.Mockito.mock(PinDao.class);
    private final OutboxDao outboxDao = org.mockito.Mockito.mock(OutboxDao.class);
    private final ReactionDao reactionDao = org.mockito.Mockito.mock(ReactionDao.class);
    private final MessageDao messageDao = org.mockito.Mockito.mock(MessageDao.class);
    private final MessageTranslationDao messageTranslationDao =
            org.mockito.Mockito.mock(MessageTranslationDao.class);
    private final ConversationDao conversationDao =
            org.mockito.Mockito.mock(ConversationDao.class);

    @Before
    public void setUp() {
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(appDatabase).runInTransaction(any(Runnable.class));
        when(appDatabase.syncCursorDao()).thenReturn(syncCursorDao);
        when(appDatabase.pinDao()).thenReturn(pinDao);
        when(appDatabase.outboxDao()).thenReturn(outboxDao);
        when(appDatabase.reactionDao()).thenReturn(reactionDao);
        when(appDatabase.messageDao()).thenReturn(messageDao);
        when(appDatabase.messageTranslationDao()).thenReturn(messageTranslationDao);
        when(appDatabase.conversationDao()).thenReturn(conversationDao);

        // The pre-fix shape ended every delete on blockingAwait(); any
        // Completable reached inside the transaction fails the test loudly.
        Completable forbidden = Completable.error(
                new AssertionError("async DAO call inside runInTransaction (issue #184)"));
        when(pinDao.deleteForConversation(any())).thenReturn(forbidden);
        when(outboxDao.deleteForConversation(any(), any())).thenReturn(forbidden);
        when(outboxDao.delete(any(), any())).thenReturn(forbidden);
        when(reactionDao.deleteForConversation(any())).thenReturn(forbidden);
        when(messageDao.deleteForConversation(any())).thenReturn(forbidden);
        when(messageTranslationDao.deleteForConversation(any())).thenReturn(forbidden);
        when(conversationDao.deleteById(any())).thenReturn(forbidden);
    }

    @Test
    public void deleteInTransactionUsesOnlySynchronousDaoCalls() {
        AtomicBoolean ranInsideTransaction = new AtomicBoolean(false);
        doAnswer(invocation -> {
            ranInsideTransaction.set(true);
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(appDatabase).runInTransaction(any(Runnable.class));

        appDatabase.runInTransaction(() ->
                LocalConversationCleaner.deleteInTransaction(
                        appDatabase, "conv-1", "owner-1"));

        org.junit.Assert.assertTrue(ranInsideTransaction.get());
        verify(syncCursorDao).delete("owner-1", "conv-1");
        verify(pinDao).deleteForConversationInTransaction("conv-1");
        verify(outboxDao).deleteForConversationInTransaction("conv-1", "owner-1");
        verify(reactionDao).deleteForConversationInTransaction("conv-1");
        verify(messageDao).deleteForConversationInTransaction("conv-1");
        verify(messageTranslationDao).deleteForConversationInTransaction("conv-1");
        verify(conversationDao).deleteByIdInTransaction("conv-1");
    }
}

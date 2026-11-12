package com.nexachat.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.data.SyncEngine;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Before;
import org.junit.Test;

public final class NotificationAdmissionTest {
    private static final long CURRENT_EPOCH = 11L;
    private final TokenRefresher tokenRefresher = mock(TokenRefresher.class);
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final AppDatabase appDatabase = mock(AppDatabase.class);
    private final ConversationDao conversationDao = mock(ConversationDao.class);
    private final SyncEngine syncEngine = mock(SyncEngine.class);
    private NotificationAdmission admission;

    @Before
    public void setUp() {
        when(tokenRefresher.sessionEpoch()).thenReturn(CURRENT_EPOCH);
        when(tokenRefresher.isSessionCurrent(CURRENT_EPOCH)).thenReturn(true);
        when(tokenRefresher.sessionSignals()).thenReturn(Flowable.empty());
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("account-a"), System.currentTimeMillis() + 60_000L, "refresh")));
        when(conversationDao.byId("conversation-1"))
                .thenReturn(Maybe.just(mock(ConversationEntity.class)));
        when(conversationDao.byIdSync("conversation-1"))
                .thenReturn(mock(ConversationEntity.class));
        when(syncEngine.sync()).thenReturn(Completable.complete());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(appDatabase).runInTransaction(any(Runnable.class));
        when(tokenRefresher.runIfSessionCurrent(eq(CURRENT_EPOCH), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });
        admission = new NotificationAdmission(tokenRefresher, tokenStore, appDatabase,
                conversationDao, syncEngine, new Moshi.Builder().build(), Schedulers.trampoline());
    }

    @Test
    public void admitsOnlyTheCurrentRecipientWithCachedMembership() {
        NotificationAdmission.NotificationSession session = admission
                .admitPush("account-a", "conversation-1").blockingGet();

        assertEquals("account-a", session.recipientId());
        assertEquals(CURRENT_EPOCH, session.sessionEpoch());
        assertTrue(admission.isCurrent("account-a", CURRENT_EPOCH, "conversation-1").blockingGet());
    }

    @Test
    public void accountSwitchAndMembershipRemovalFailClosed() {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("account-b"), System.currentTimeMillis() + 60_000L, "refresh")));

        assertTrue(admission.admitPush("account-a", "conversation-1").isEmpty().blockingGet());
        assertFalse(admission.isCurrent("account-a", CURRENT_EPOCH, "conversation-1").blockingGet());
        verify(conversationDao, never()).byId("conversation-1");
    }

    @Test
    public void membershipRemovedDuringSyncCannotReachNotificationAdmission() {
        when(conversationDao.byId("conversation-1")).thenReturn(
                Maybe.just(mock(ConversationEntity.class)), Maybe.empty());

        assertFalse(admission.syncAndCheck("account-a", CURRENT_EPOCH,
                "conversation-1").blockingGet());

        verify(syncEngine).sync();
    }

    @Test
    public void sameAccountAfterLogoutCannotReuseTheOldEpoch() {
        when(tokenRefresher.sessionEpoch()).thenReturn(CURRENT_EPOCH + 1L);
        when(tokenRefresher.isSessionCurrent(CURRENT_EPOCH + 1L)).thenReturn(true);
        when(tokenRefresher.isSessionCurrent(CURRENT_EPOCH)).thenReturn(false);

        assertFalse(admission.isCurrent("account-a", CURRENT_EPOCH,
                "conversation-1").blockingGet());
        verify(conversationDao, never()).byId("conversation-1");
    }

    @Test
    public void finalMembershipCheckCanSuppressPublication() {
        when(conversationDao.byIdSync("conversation-1")).thenReturn(null);
        java.util.concurrent.atomic.AtomicBoolean sideEffect =
                new java.util.concurrent.atomic.AtomicBoolean();

        assertFalse(admission.runIfCurrent("account-a", CURRENT_EPOCH,
                "conversation-1", ignored -> sideEffect.set(true)));

        assertFalse(sideEffect.get());
        verify(appDatabase).runInTransaction(any(Runnable.class));
    }

    @Test
    public void finalTokenSubjectCheckBlocksAReplacementBeforeItsEpochSignal() {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("account-b"), System.currentTimeMillis() + 60_000L, "refresh")));

        assertFalse(admission.runIfCurrent("account-a", CURRENT_EPOCH,
                "conversation-1", ignored -> { }));

        verify(appDatabase, never()).runInTransaction(any(Runnable.class));
    }

    @Test
    public void sideEffectAndFinalMembershipCheckShareTheDatabaseTransaction() {
        java.util.concurrent.atomic.AtomicBoolean transactionActive =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean sideEffectRanInTransaction =
                new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            transactionActive.set(true);
            try {
                ((Runnable) invocation.getArgument(0)).run();
            } finally {
                transactionActive.set(false);
            }
            return null;
        }).when(appDatabase).runInTransaction(any(Runnable.class));
        when(conversationDao.byIdSync("conversation-1")).thenAnswer(invocation ->
                transactionActive.get() ? mock(ConversationEntity.class) : null);

        assertTrue(admission.runIfCurrent("account-a", CURRENT_EPOCH,
                "conversation-1", ignored ->
                        sideEffectRanInTransaction.set(transactionActive.get())));

        assertTrue(sideEffectRanInTransaction.get());
    }

    private static String jwt(String userId) {
        String payload = "{\"sub\":\"" + userId + "\"}";
        return "e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}

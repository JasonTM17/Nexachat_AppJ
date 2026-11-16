package com.nexachat.core.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.SessionSnapshot;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import org.junit.Test;

/**
 * A process restored from a task snapshot (or started for WorkManager/push)
 * skips the splash refresh that bootstraps session admission. Without a warm-up
 * the in-memory epoch stays 0 with admission closed, so every fenced sync write
 * and outbox acknowledgement throws CancellationException while the durable
 * session is healthy.
 */
public final class TokenRefresherWarmAdmissionTest {

    @Test
    public void warmSessionAdmissionAdmitsPersistedActiveSession() {
        TokenRefresher refresher = new TokenRefresher(
                storeWithSnapshot(new SessionSnapshot(7L, SessionSnapshot.Lifecycle.ACTIVE)),
                new SessionEvents(),
                (Provider<AuthApi>) () -> mock(AuthApi.class));

        // Before any bootstrap the session is inert: this is the stuck state
        // observed in the wild (epoch=0, admission closed, acks cancelled).
        assertFalse(refresher.isSessionCurrent(0L));
        assertFalse(refresher.isSessionCurrent(7L));

        refresher.warmSessionAdmission();

        assertTrue(refresher.isSessionCurrent(7L));
        AtomicBoolean ran = new AtomicBoolean();
        assertTrue(refresher.runIfSessionCurrent(7L, () -> ran.set(true)));
        assertTrue(ran.get());
    }

    @Test
    public void warmSessionAdmissionDoesNotResurrectClosedSession() {
        TokenRefresher refresher = new TokenRefresher(
                storeWithSnapshot(new SessionSnapshot(4L, SessionSnapshot.Lifecycle.CLOSED)),
                new SessionEvents(),
                (Provider<AuthApi>) () -> mock(AuthApi.class));

        refresher.warmSessionAdmission();

        // A genuinely closed/reauthenticated session must stay fenced out.
        assertFalse(refresher.isSessionCurrent(4L));
        assertFalse(refresher.runIfSessionCurrent(4L, () -> {
            throw new AssertionError("closed session must not run work");
        }));
    }

    @Test
    public void warmSessionAdmissionIsIdempotent() {
        TokenRefresher refresher = new TokenRefresher(
                storeWithSnapshot(new SessionSnapshot(3L, SessionSnapshot.Lifecycle.ACTIVE)),
                new SessionEvents(),
                (Provider<AuthApi>) () -> mock(AuthApi.class));

        refresher.warmSessionAdmission();
        refresher.warmSessionAdmission();

        assertTrue(refresher.isSessionCurrent(3L));
    }

    private static TokenStore storeWithSnapshot(SessionSnapshot snapshot) {
        TokenStore store = mock(TokenStore.class);
        AtomicReference<Tokens> state =
                new AtomicReference<>(new Tokens("access-live", 9_000L, "rt-live"));
        when(store.readTokens()).thenAnswer(ignored -> Maybe.defer(() ->
                state.get() == null ? Maybe.empty() : Maybe.just(state.get())));
        when(store.saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenReturn(Completable.complete());
        when(store.readSessionSnapshot()).thenReturn(Single.just(snapshot));
        when(store.bootstrapLegacySessionIfNeeded()).thenReturn(Single.just(snapshot.getEpoch()));
        when(store.readPendingRefresh()).thenReturn(Maybe.empty());
        when(store.getHasValidRefresh()).thenReturn(Flowable.just(true));
        when(store.reservePendingRefresh(anyLong(), anyString(), anyString()))
                .thenReturn(Single.just(true));
        when(store.reserveSessionClosing(anyLong())).thenReturn(Single.just(0L));
        when(store.markSessionClosed(anyLong())).thenReturn(Completable.complete());
        when(store.markSessionReauthRequired(anyLong())).thenReturn(Completable.complete());
        when(store.clear()).thenReturn(Completable.complete());
        return store;
    }
}

package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

        // Before any bootstrap the replayed admission state is closed: this is
        // the stuck state observed in the wild (epoch=0, admission closed, acks
        // cancelled). The replayed stream observes it without bootstrapping.
        assertFalse(refresher.sessionAdmission().blockingFirst());

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
    public void warmSessionAdmissionDoesNotResurrectClosingSession() {
        TokenRefresher refresher = new TokenRefresher(
                storeWithSnapshot(new SessionSnapshot(5L, SessionSnapshot.Lifecycle.CLOSING)),
                new SessionEvents(),
                (Provider<AuthApi>) () -> mock(AuthApi.class));

        refresher.warmSessionAdmission();

        // A session mid-close (logout or rejection in flight) must stay fenced
        // out just like a fully closed one.
        assertFalse(refresher.isSessionCurrent(5L));
        assertFalse(refresher.runIfSessionCurrent(5L, () -> {
            throw new AssertionError("closing session must not run work");
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

    @Test
    public void fencedCheckBeforeWarmBootstrapSelfAdmits() {
        TokenRefresher refresher = new TokenRefresher(
                storeWithSnapshot(new SessionSnapshot(7L, SessionSnapshot.Lifecycle.ACTIVE)),
                new SessionEvents(),
                (Provider<AuthApi>) () -> mock(AuthApi.class));

        // A fenced operation that arrives before the async warm has run must
        // bootstrap from the durable snapshot itself instead of observing the
        // pre-bootstrap (unadmitted, epoch=0) state.
        AtomicBoolean ran = new AtomicBoolean();
        assertTrue(refresher.runIfSessionCurrent(7L, () -> ran.set(true)));
        assertTrue(ran.get());
        assertTrue(refresher.isSessionCurrent(7L));
        assertEquals(7L, refresher.sessionEpoch());
    }

    @Test
    public void fencedCheckDuringInFlightWarmWaitsAndSeesAdmittedSession() throws Exception {
        CountDownLatch snapshotReadEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshotRead = new CountDownLatch(1);
        TokenStore store = storeWithSnapshot(
                new SessionSnapshot(7L, SessionSnapshot.Lifecycle.ACTIVE));
        when(store.readSessionSnapshot()).thenReturn(Single.fromCallable(() -> {
            snapshotReadEntered.countDown();
            if (!releaseSnapshotRead.await(10L, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release the snapshot read");
            }
            return new SessionSnapshot(7L, SessionSnapshot.Lifecycle.ACTIVE);
        }));
        TokenRefresher refresher = new TokenRefresher(
                store, new SessionEvents(), (Provider<AuthApi>) () -> mock(AuthApi.class));

        // The app dispatches the warm on a worker thread. A fenced operation
        // that starts while its blocking read is in flight must wait on the
        // same lock, then see the admitted session — never fail fast against
        // the pre-bootstrap state.
        Thread warm = new Thread(refresher::warmSessionAdmission, "warm-admission");
        warm.start();
        assertTrue(snapshotReadEntered.await(10L, TimeUnit.SECONDS));

        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean applied = new AtomicBoolean();
        Thread fenced = new Thread(() -> {
            applied.set(refresher.runIfSessionCurrent(7L, () -> ran.set(true)));
        }, "fenced-ack");
        fenced.start();

        fenced.join(500L);
        assertTrue("fenced check must wait for the in-flight warm, not fail fast",
                fenced.isAlive());
        releaseSnapshotRead.countDown();
        fenced.join(10_000L);
        warm.join(10_000L);

        assertTrue(applied.get());
        assertTrue(ran.get());
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

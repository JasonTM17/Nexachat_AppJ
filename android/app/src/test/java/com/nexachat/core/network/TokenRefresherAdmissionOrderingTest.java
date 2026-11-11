package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.storage.SessionSnapshot;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Serialized admission emissions under logout versus late establishment. */
public final class TokenRefresherAdmissionOrderingTest {

    @Test
    public void lateEstablishedDuringLogoutCannotPublishTrueAfterFalse() throws Exception {
        TokenStore store = storeWithActiveSession();
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), () -> mock(AuthApi.class));
        CountDownLatch initialAdmitted = new CountDownLatch(1);
        CountDownLatch loggedOutAdmission = new CountDownLatch(1);
        AtomicBoolean sawAdmission = new AtomicBoolean();
        TestSubscriber<Boolean> admission = refresher.sessionAdmission()
                .doOnNext(value -> {
                    if (value) {
                        sawAdmission.set(true);
                        initialAdmitted.countDown();
                    } else if (sawAdmission.get()) {
                        loggedOutAdmission.countDown();
                    }
                })
                .test();
        refresher.onSessionEstablished(1L);
        assertTrue("initial admission should be delivered", initialAdmitted.await(5, TimeUnit.SECONDS));

        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        refresher.admissionTestHookBeforeTrue = () -> {
            hookEntered.countDown();
            try {
                if (!allowPublish.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("timeout waiting to publish");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };

        Thread late = new Thread(() -> refresher.onSessionEstablished(2L));
        late.start();
        assertTrue(hookEntered.await(30, TimeUnit.SECONDS));

        refresher.onLogout();
        assertFalse(refresher.isSessionCurrent(2L));
        assertTrue("closed admission should be delivered", loggedOutAdmission.await(5, TimeUnit.SECONDS));
        List<Boolean> afterLogout = admission.values();
        assertEquals(Boolean.FALSE, afterLogout.get(afterLogout.size() - 1));

        allowPublish.countDown();
        late.join(30_000L);

        List<Boolean> events = admission.values();
        int lastFalse = -1;
        for (int i = 0; i < events.size(); i++) {
            if (!events.get(i)) {
                lastFalse = i;
            }
        }
        for (int i = lastFalse + 1; i < events.size(); i++) {
            assertFalse("admission must stay false after logout", events.get(i));
        }
        assertFalse(refresher.isSessionCurrent(2L));
    }

    @Test
    public void blockedAdmissionConsumerDoesNotDelayCloseOrReopenANewerEpoch() throws Exception {
        TokenStore store = storeWithActiveSession();
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), () -> mock(AuthApi.class));
        CountDownLatch firstAdmissionEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAdmission = new CountDownLatch(1);
        CountDownLatch newerEpochDelivered = new CountDownLatch(1);
        AtomicInteger trueSignals = new AtomicInteger();
        TestSubscriber<Long> epochChanges = refresher.sessionEpochChanges()
                .doOnNext(epoch -> {
                    if (epoch == 3L) newerEpochDelivered.countDown();
                })
                .test();
        TestSubscriber<Boolean> admission = refresher.sessionAdmission()
                .doOnNext(value -> {
                    if (!value) {
                        return;
                    }
                    if (trueSignals.incrementAndGet() == 1) {
                        firstAdmissionEntered.countDown();
                        try {
                            if (!releaseFirstAdmission.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("test did not release the admission observer");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }
                })
                .test();

        refresher.onSessionEstablished(1L);
        assertTrue("first admission observer should block",
                firstAdmissionEntered.await(5, TimeUnit.SECONDS));

        Thread logout = new Thread(refresher::onLogout);
        logout.start();
        logout.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse("logout must not wait for an admission observer", logout.isAlive());
        assertFalse("logout must close the active epoch synchronously",
                refresher.isSessionCurrent(1L));

        refresher.onSessionEstablished(3L);
        assertTrue("new epoch must become current synchronously", refresher.isSessionCurrent(3L));
        releaseFirstAdmission.countDown();

        assertTrue("new epoch should be delivered",
                newerEpochDelivered.await(5, TimeUnit.SECONDS));
        List<Boolean> values = admission.values();
        assertEquals(Boolean.TRUE, values.get(values.size() - 1));
        assertTrue(refresher.isSessionCurrent(3L));
        assertEquals(1, trueSignals.get());
    }

    private static TokenStore storeWithActiveSession() {
        TokenStore store = mock(TokenStore.class);
        whenStoreSnapshot(store, 1L);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("a0", Long.MAX_VALUE, "r0")));
        when(store.reserveSessionClosing(anyLong())).thenReturn(Single.just(2L));
        return store;
    }

    private static void whenStoreSnapshot(TokenStore store, long epoch) {
        when(store.readSessionSnapshot())
                .thenReturn(Single.just(new SessionSnapshot(epoch, SessionSnapshot.Lifecycle.ACTIVE)));
    }
}

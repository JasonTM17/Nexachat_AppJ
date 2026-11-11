package com.nexachat.core.realtime.ephemeral;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.TokenRefresher;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class FirebaseAuthQueueTest {
    @Test
    public void closedEpochCannotAppendLateSignInAheadOfNextAccount() throws Exception {
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        Object admissionLock = new Object();
        AtomicLong currentEpoch = new AtomicLong(7L);
        AtomicBoolean admissionOpen = new AtomicBoolean(true);
        CountDownLatch oldBootstrapAtAdmission = new CountDownLatch(1);
        CountDownLatch allowOldBootstrapToContinue = new CountDownLatch(1);
        when(tokenRefresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            long expectedEpoch = invocation.getArgument(0);
            Runnable action = invocation.getArgument(1);
            oldBootstrapAtAdmission.countDown();
            if (!allowOldBootstrapToContinue.await(2L, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release old bootstrap");
            }
            synchronized (admissionLock) {
                if (!admissionOpen.get() || currentEpoch.get() != expectedEpoch) return false;
                action.run();
                return true;
            }
        });

        FirebaseAuthQueue queue = new FirebaseAuthQueue();
        List<String> authMutations = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> oldBootstrapError = new AtomicReference<>();
        Thread oldBootstrap = new Thread(() -> queue.enqueueIfSessionCurrent(
                        tokenRefresher,
                        7L,
                        Completable.fromAction(() -> authMutations.add("old-account sign-in")))
                .subscribe(() -> { }, oldBootstrapError::set));
        oldBootstrap.start();

        assertTrue(oldBootstrapAtAdmission.await(2L, TimeUnit.SECONDS));
        synchronized (admissionLock) {
            admissionOpen.set(false);
            currentEpoch.set(8L);
        }
        allowOldBootstrapToContinue.countDown();
        oldBootstrap.join(2_000L);
        assertFalse(oldBootstrap.isAlive());
        assertNotNull(oldBootstrapError.get());

        synchronized (admissionLock) {
            admissionOpen.set(true);
        }
        queue.enqueueIfSessionCurrent(
                        tokenRefresher,
                        8L,
                        Completable.fromAction(() -> authMutations.add("new-account sign-in")))
                .blockingAwait();

        assertEquals(List.of("new-account sign-in"), authMutations);
    }

    @Test
    public void pendingOldSignInIsFencedAndIsolatedFromNextRuntime() {
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        Object admissionLock = new Object();
        AtomicLong currentEpoch = new AtomicLong(7L);
        AtomicBoolean admissionOpen = new AtomicBoolean(true);
        when(tokenRefresher.isSessionCurrent(anyLong())).thenAnswer(invocation ->
                admissionOpen.get() && currentEpoch.get() == invocation.<Long>getArgument(0));
        when(tokenRefresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            synchronized (admissionLock) {
                long expectedEpoch = invocation.getArgument(0);
                if (!admissionOpen.get() || currentEpoch.get() != expectedEpoch) return false;
                invocation.<Runnable>getArgument(1).run();
                return true;
            }
        });

        String oldRuntime = FirebaseAuthQueue.runtimeAppName(7L, 1L);
        String newRuntime = FirebaseAuthQueue.runtimeAppName(8L, 2L);
        assertFalse(oldRuntime.equals(newRuntime));

        FirebaseAuthQueue queue = new FirebaseAuthQueue();
        Map<String, String> runtimeUsers = new ConcurrentHashMap<>();
        List<String> authMutations = Collections.synchronizedList(new ArrayList<>());
        CompletableSubject pendingOldSignIn = CompletableSubject.create();
        AtomicReference<Throwable> oldSignInError = new AtomicReference<>();
        queue.enqueueSessionOperation(
                        tokenRefresher,
                        7L,
                        () -> tokenRefresher.isSessionCurrent(7L),
                        Completable.fromAction(() -> authMutations.add("old sign-in started"))
                                .andThen(pendingOldSignIn.doOnComplete(() -> {
                                    runtimeUsers.put(oldRuntime, "old-account");
                                    authMutations.add("old sign-in completed");
                                })),
                        () -> Completable.fromAction(() -> {
                            runtimeUsers.remove(oldRuntime);
                            authMutations.add("stale runtime signed out");
                        }))
                .subscribe(() -> { }, oldSignInError::set);
        assertEquals(List.of("old sign-in started"), authMutations);

        synchronized (admissionLock) {
            admissionOpen.set(false);
            currentEpoch.set(8L);
        }
        queue.enqueue(Completable.fromAction(() -> {
            runtimeUsers.remove(oldRuntime);
            authMutations.add("stopped old runtime");
        }));
        synchronized (admissionLock) {
            admissionOpen.set(true);
        }
        io.reactivex.rxjava3.observers.TestObserver<Void> newSignIn = queue.enqueueSessionOperation(
                        tokenRefresher,
                        8L,
                        () -> tokenRefresher.isSessionCurrent(8L),
                        Completable.fromAction(() -> {
                            runtimeUsers.remove(newRuntime);
                            authMutations.add("new runtime startup sign-out");
                        }).andThen(Completable.fromAction(() -> {
                            runtimeUsers.put(newRuntime, "new-account");
                            authMutations.add("new sign-in");
                        })),
                        () -> Completable.fromAction(() -> runtimeUsers.remove(newRuntime)))
                .test();

        pendingOldSignIn.onComplete();

        newSignIn.assertComplete();
        assertNotNull(oldSignInError.get());
        assertEquals("new-account", runtimeUsers.get(newRuntime));
        assertFalse(runtimeUsers.containsKey(oldRuntime));
        assertEquals(List.of(
                "old sign-in started",
                "old sign-in completed",
                "stale runtime signed out",
                "stopped old runtime",
                "new runtime startup sign-out",
                "new sign-in"), authMutations);
    }
}

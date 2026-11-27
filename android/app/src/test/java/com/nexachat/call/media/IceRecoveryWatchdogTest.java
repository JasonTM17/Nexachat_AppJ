package com.nexachat.call.media;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.webrtc.PeerConnection.IceConnectionState;

public final class IceRecoveryWatchdogTest {
    private final TestScheduler clock = new TestScheduler();
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final IceRecoveryWatchdog watchdog = new IceRecoveryWatchdog(executor);
    private int connectedCallbacks;
    private int failures;

    @Before
    public void useDeterministicTimer() {
        when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
            Disposable timer = clock.scheduleDirect(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            when(future.cancel(false)).thenAnswer(ignored -> {
                timer.dispose();
                return true;
            });
            return future;
        });
    }

    @Test
    public void establishedCallCanRecoverAfterNativeFailureAndLateIceRestart() {
        state(IceConnectionState.DISCONNECTED, true);
        advanceTo(7);
        state(IceConnectionState.FAILED, true);
        advanceTo(16);
        assertEquals(0, failures);

        state(IceConnectionState.CHECKING, true);
        advanceTo(19);
        state(IceConnectionState.CONNECTED, true);
        advanceTo(45);
        assertEquals(1, connectedCallbacks);
        assertEquals(0, failures);
    }

    @Test
    public void aPermanentLossExpiresOnceAtTheOriginalDeadline() {
        state(IceConnectionState.DISCONNECTED, true);
        advanceTo(7);
        state(IceConnectionState.FAILED, true);
        advanceTo(16);
        state(IceConnectionState.CHECKING, true);
        advanceTo(24);
        state(IceConnectionState.DISCONNECTED, true);
        advanceTo(29);
        assertEquals(0, failures);
        advanceTo(30);
        assertEquals(1, failures);
        advanceTo(60);
        assertEquals(1, failures);
    }

    @Test
    public void initialNegotiationFailureStillEndsImmediately() {
        state(IceConnectionState.FAILED, false);
        assertEquals(1, failures);
    }

    @Test
    public void releasingCallCancelsPendingRecovery() {
        state(IceConnectionState.DISCONNECTED, true);
        advanceTo(10);
        watchdog.cancel();
        advanceTo(60);
        assertEquals(0, failures);
    }

    private void state(IceConnectionState state, boolean established) {
        watchdog.onState(state, established, () -> connectedCallbacks++, () -> failures++);
    }

    private void advanceTo(long seconds) {
        clock.advanceTimeTo(seconds, TimeUnit.SECONDS);
    }
}

package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Session epoch advances without admission closing must not strand attemptActive. */
public final class RealtimeSessionEpochAdvanceTest {

    @Test
    public void epochAdvanceWithoutAdmissionTransitionStartsNewAttempt() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> tokens = BehaviorProcessor.createDefault(true);
        BehaviorProcessor<Boolean> admission = BehaviorProcessor.createDefault(true);
        AtomicLong epoch = new AtomicLong(1L);
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(admission);
        when(refresher.sessionEpoch()).thenAnswer(ignored -> epoch.get());
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(invocation -> {
            long expected = invocation.getArgument(0);
            return admission.getValue() && expected == epoch.get();
        });

        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(any())).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.never();
        });

        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(tokens);
        when(store.readTokens()).thenReturn(
                Maybe.just(new Tokens("access", Long.MAX_VALUE, "refresh")));

        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        AppForegroundState foreground = () -> Flowable.just(false);
        RealtimeSession session = new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(() -> 0.0), monitor, foreground, scheduler);
        scheduler.triggerActions();
        assertEquals(1, connects.get());

        epoch.set(2L);
        session.ensureConnected();
        scheduler.triggerActions();
        assertEquals("epoch advance must clear the stranded attempt gate", 2, connects.get());
    }
}

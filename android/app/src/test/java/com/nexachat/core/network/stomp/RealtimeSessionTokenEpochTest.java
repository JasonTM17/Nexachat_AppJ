package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.TokenRefreshOutcome;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Session-epoch and attempt fencing for realtime refresh and account switches. */
public final class RealtimeSessionTokenEpochTest {

    @Test
    public void preflightConnectUsesRotatedBearerAndRecoveryUsesSameToken() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        stubAdmission(refresher, 1L);
        when(refresher.refreshOutcomeIfNeeded("a0"))
                .thenReturn(Single.just(new TokenRefreshOutcome.FreshToken("a1")));
        AtomicReference<String> authRecoveryToken = new AtomicReference<>();
        when(refresher.refreshOutcomeIfNeeded("a1"))
                .thenAnswer(invocation -> {
                    authRecoveryToken.set(invocation.getArgument(0));
                    return Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE);
                });
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(eq("a1"), any())).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed("HTTP_401", true));
        });
        RealtimeSession session = session(storeWithTokens("a0", 0L), stomp, refresher, scheduler);
        scheduler.triggerActions();
        verify(refresher, times(1)).refreshOutcomeIfNeeded("a0");
        verify(refresher, times(1)).refreshOutcomeIfNeeded("a1");
        verify(stomp, times(1)).connect(eq("a1"), any());
        assertEquals("a1", authRecoveryToken.get());
        assertEquals(1, connects.get());
    }

    @Test
    public void staleRecoveryFromPriorAccountDoesNotStartExtraSocketForB() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> tokens = BehaviorProcessor.createDefault(true);
        BehaviorProcessor<Boolean> admission = BehaviorProcessor.createDefault(true);
        AtomicLong epoch = new AtomicLong(1L);
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(admission);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenAnswer(ignored -> epoch.get());
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(invocation -> {
            long expected = invocation.getArgument(0);
            return admission.getValue() && expected == epoch.get();
        });
        stubSessionActionGate(refresher, admission, epoch::get);

        SingleSubject<TokenRefreshOutcome> pendingRefreshA = SingleSubject.create();
        when(refresher.refreshOutcomeIfNeeded("access-a")).thenReturn(pendingRefreshA);
        when(refresher.refreshOutcomeIfNeeded("access-b"))
                .thenReturn(Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE));

        AtomicInteger connectsForB = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(any(), any())).thenAnswer(invocation -> {
            String bearer = invocation.getArgument(0);
            if ("access-a".equals(bearer)) {
                return Flowable.just(new StompClient.State.Failed("HTTP_401", true));
            }
            if ("access-b".equals(bearer)) {
                connectsForB.incrementAndGet();
                return Flowable.just(new StompClient.State.Failed("HTTP_401", true));
            }
            return Flowable.never();
        });

        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(tokens);
        AtomicReference<String> access = new AtomicReference<>("access-a");
        when(store.readTokens()).thenAnswer(ignored -> Maybe.just(
                new Tokens(access.get(), Long.MAX_VALUE, "refresh")));

        RealtimeSession session = session(store, stomp, refresher, scheduler);
        scheduler.triggerActions();

        epoch.set(2L);
        admission.onNext(false);
        admission.onNext(true);
        access.set("access-b");
        scheduler.triggerActions();
        session.ensureConnected();
        scheduler.triggerActions();

        int connectsForBBeforePending = connectsForB.get();
        assertTrue(connectsForBBeforePending >= 1);
        pendingRefreshA.onSuccess(TokenRefreshOutcome.TransientFailure.INSTANCE);
        scheduler.triggerActions();

        assertEquals(connectsForBBeforePending, connectsForB.get());
    }

    @Test
    public void logoutFencesLateStompFrameFromThePreviousSession() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> admission = BehaviorProcessor.createDefault(true);
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(admission);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenReturn(8L);
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(invocation ->
                admission.getValue() && invocation.getArgument(0, Long.class) == 8L);
        stubSessionActionGate(refresher, admission, () -> 8L);

        PublishSubject<StompClient.State> attempt = PublishSubject.create();
        PublishSubject<com.nexachat.core.network.stomp.StompFrame> frames = PublishSubject.create();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(frames.toFlowable(BackpressureStrategy.LATEST));
        when(stomp.connect(any(), any())).thenReturn(Flowable.concat(
                Flowable.just(StompClient.State.Open.INSTANCE),
                attempt.toFlowable(BackpressureStrategy.LATEST)));
        RealtimeSession session = new RealtimeSession(
                stomp, loggedInStore(), refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(() -> 0.0), onlineNever(), foregroundNever(), scheduler, scheduler);
        var events = session.getEvents().test();
        scheduler.triggerActions();
        assertTrue(session.isCurrentlyOpen());

        frames.onNext(typingFrame("old session before logout"));
        scheduler.triggerActions();
        assertEquals(1, events.values().size());

        admission.onNext(false);
        scheduler.triggerActions();
        frames.onNext(typingFrame("late callback after logout"));
        scheduler.triggerActions();

        assertFalse(session.isCurrentlyOpen());
        assertEquals("old-session callback must not reach consumers", 1, events.values().size());
        verify(stomp, times(1)).connect(eq("access"), any());
        verify(stomp, atLeast(1)).close();
    }

    @Test
    public void supersededAttemptCompletionDoesNotPublishOpen() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        stubAdmission(refresher, 5L);
        PublishSubject<StompClient.State> firstAttempt = PublishSubject.create();
        AtomicInteger connectCalls = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(any(), any())).thenAnswer(ignored -> {
            if (connectCalls.getAndIncrement() == 0) {
                return firstAttempt.toFlowable(BackpressureStrategy.LATEST);
            }
            return Flowable.never();
        });
        RealtimeSession session = session(loggedInStore(), stomp, refresher, scheduler);
        scheduler.triggerActions();
        firstAttempt.onNext(new StompClient.State.Failed(StompClient.REASON_OFFLINE));
        scheduler.triggerActions();
        session.ensureConnected();
        scheduler.triggerActions();
        assertFalse(session.isCurrentlyOpen());
        firstAttempt.onNext(StompClient.State.Open.INSTANCE);
        scheduler.triggerActions();
        assertFalse(session.isCurrentlyOpen());
    }

    @Test
    public void admissionMustOpenBeforeConnectDespiteTokensPresent() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> tokens = BehaviorProcessor.createDefault(true);
        BehaviorProcessor<Boolean> admission = BehaviorProcessor.createDefault(false);
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(admission);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenReturn(3L);
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(invocation ->
                admission.getValue() && invocation.getArgument(0, Long.class) == 3L);
        stubSessionActionGate(refresher, admission, () -> 3L);
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(any(), any())).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.never();
        });
        session(loggedInStore(tokens), stomp, refresher, scheduler);
        scheduler.advanceTimeBy(1, TimeUnit.MINUTES);
        assertEquals(0, connects.get());
        admission.onNext(true);
        scheduler.triggerActions();
        assertEquals(1, connects.get());
    }

    @Test
    public void admissionClosureClosesSocketBeforeTokensClear() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> tokens = BehaviorProcessor.createDefault(true);
        BehaviorProcessor<Boolean> admission = BehaviorProcessor.createDefault(true);
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(admission);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenReturn(4L);
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(invocation ->
                admission.getValue() && invocation.getArgument(0, Long.class) == 4L);
        stubSessionActionGate(refresher, admission, () -> 4L);
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(any(), any())).thenReturn(Flowable.concat(
                Flowable.just(StompClient.State.Open.INSTANCE),
                Flowable.never()));
        RealtimeSession session = session(loggedInStore(tokens), stomp, refresher, scheduler);
        scheduler.triggerActions();
        assertTrue(session.isCurrentlyOpen());
        admission.onNext(false);
        scheduler.triggerActions();
        verify(stomp, atLeast(1)).close();
        assertFalse(session.isCurrentlyOpen());
        assertTrue(tokens.getValue());
    }

    @Test
    public void recoveryRequiredParksWithoutExtraRotationAttempts() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        stubAdmission(refresher, 1L);
        when(refresher.refreshOutcomeIfNeeded("a0"))
                .thenReturn(Single.just(TokenRefreshOutcome.RecoveryRequired.INSTANCE));
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(any(), any())).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.never();
        });
        session(storeWithTokens("a0", 0L), stomp, refresher, scheduler);
        scheduler.triggerActions();
        assertEquals(0, connects.get());
        verify(refresher, times(1)).refreshOutcomeIfNeeded("a0");
    }

    private static TokenStore loggedInStore() {
        return loggedInStore(BehaviorProcessor.createDefault(true));
    }

    private static TokenStore loggedInStore(BehaviorProcessor<Boolean> loggedIn) {
        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(loggedIn);
        when(store.readTokens()).thenAnswer(ignored -> loggedIn.getValue()
                ? Maybe.just(new Tokens("access", Long.MAX_VALUE, "refresh"))
                : Maybe.empty());
        return store;
    }

    private static TokenStore storeWithTokens(String access, long expiresAt) {
        TokenStore store = mock(TokenStore.class);
        BehaviorProcessor<Boolean> loggedIn = BehaviorProcessor.createDefault(true);
        when(store.getHasValidRefresh()).thenReturn(loggedIn);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens(access, expiresAt, "refresh")));
        return store;
    }

    private static void stubAdmission(TokenRefresher refresher, long epoch) {
        BehaviorProcessor<Boolean> flow = BehaviorProcessor.createDefault(true);
        when(refresher.sessionAdmission()).thenReturn(flow);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenReturn(epoch);
        when(refresher.isSessionCurrent(epoch)).thenAnswer(ignored -> flow.getValue());
        stubSessionActionGate(refresher, flow, () -> epoch);
    }

    private static void stubSessionActionGate(
            TokenRefresher refresher,
            BehaviorProcessor<Boolean> admission,
            java.util.function.LongSupplier currentEpoch) {
        when(refresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            long expected = invocation.getArgument(0);
            if (!admission.getValue() || expected != currentEpoch.getAsLong()) {
                return false;
            }
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
    }

    private static RealtimeSession session(
            TokenStore store, StompClient stomp, TokenRefresher refresher, TestScheduler scheduler) {
        return new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(() -> 0.0), onlineNever(), foregroundNever(), scheduler);
    }

    private static com.nexachat.core.network.stomp.StompFrame typingFrame(String displayName) {
        return new com.nexachat.core.network.stomp.StompFrame(
                com.nexachat.core.network.stomp.StompCommand.MESSAGE,
                java.util.Map.of("destination", "/user/queue/inbox"),
                "{\"eventType\":\"USER_TYPING\",\"conversationId\":\"c-1\","
                        + "\"payload\":{\"userId\":\"user-a\",\"displayName\":\""
                        + displayName + "\"}}");
    }

    private static NetworkMonitor onlineNever() {
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        return monitor;
    }

    private static AppForegroundState foregroundNever() {
        return () -> Flowable.just(false);
    }
}

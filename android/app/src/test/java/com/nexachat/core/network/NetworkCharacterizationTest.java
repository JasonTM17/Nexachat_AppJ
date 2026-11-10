package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.network.stomp.ReconnectPolicy;
import com.nexachat.core.network.stomp.StompClient;
import com.nexachat.core.network.stomp.StompCommand;
import com.nexachat.core.network.stomp.StompFrame;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.SessionSnapshot;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Single;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/** Java/Rx regression coverage for refresh races and realtime reconnect behavior. */
public final class NetworkCharacterizationTest {

    @Test
    public void logoutDuringRefreshPreventsTokenWriteBack() throws Exception {
        TokenStore store = tokenStore(new Tokens("expired", 0L, "refresh"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        when(api.refresh(any())).thenReturn(call);
        when(api.refresh(anyString(), any())).thenReturn(call);
        when(call.execute()).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Response.success(new AuthResponse(
                    new UserDto("u1", "+84900000001", "User"), "new-access", 900, "new-refresh"));
        });
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        Thread refresh = new Thread(refresher::refreshBlocking);
        refresh.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        refresher.onLogout();
        store.clear().blockingAwait();
        release.countDown();
        refresh.join(10_000);
        assertNull(store.readTokens().blockingGet());
        verify(store, never()).saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class));
    }

    @Test
    public void reconnectResubscribesAndPreservesDuplicateDeliveries() {
        TestScheduler scheduler = new TestScheduler();
        TokenStore store = tokenStore(new Tokens("access", Long.MAX_VALUE, "refresh"));
        io.reactivex.rxjava3.processors.FlowableProcessor<StompFrame> frameSource =
                PublishProcessor.<StompFrame>create().toSerialized();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(frameSource.onBackpressureBuffer());
        when(stomp.subscribe(any())).thenReturn(Flowable.empty());
        Flowable<StompClient.State> openedAndClosed = Flowable.just(
                StompClient.State.Open.INSTANCE,
                new StompClient.State.Closed(1000, "test"));
        when(stomp.connect(any())).thenReturn(openedAndClosed, openedAndClosed);
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(Flowable.just(true));
        when(refresher.sessionEpoch()).thenReturn(1L);
        when(refresher.isSessionCurrent(anyLong())).thenReturn(true);
        RealtimeSession session = new RealtimeSession(
                stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(), monitor, () -> Flowable.just(false), scheduler);

        List<ChatEvent> events = new ArrayList<>();
        session.getEvents().subscribe(events::add);
        scheduler.triggerActions();
        verify(stomp, times(1)).subscribe(RealtimeSession.USER_INBOX_DESTINATION);
        verify(stomp, times(1)).subscribe(RealtimeSession.USER_ERRORS_DESTINATION);
        verify(stomp, times(1)).subscribe(RealtimeSession.USER_PRESENCE_DESTINATION);

        StompFrame duplicate = messageFrame();
        frameSource.onNext(duplicate);
        frameSource.onNext(duplicate);
        assertEquals(2, events.size());
        assertTrue(events.get(0) instanceof ChatEvent.MessageCreated);
        assertTrue(events.get(1) instanceof ChatEvent.MessageCreated);
        assertEquals(((ChatEvent.MessageCreated) events.get(0)).getMessage().getId(),
                ((ChatEvent.MessageCreated) events.get(1)).getMessage().getId());

        scheduler.advanceTimeBy(1_300L, TimeUnit.MILLISECONDS);
        verify(stomp, times(2)).subscribe(RealtimeSession.USER_PRESENCE_DESTINATION);
        session.ensureConnected();
        scheduler.triggerActions();
        verify(stomp, times(3)).subscribe(RealtimeSession.USER_PRESENCE_DESTINATION);
    }

    private static TokenStore tokenStore(Tokens initial) {
        TokenStore store = mock(TokenStore.class);
        AtomicReference<Tokens> current = new AtomicReference<>(initial);
        BehaviorProcessor<Boolean> hasToken = BehaviorProcessor.createDefault(initial != null);
        when(store.readTokens()).thenAnswer(ignored -> Maybe.defer(() ->
                current.get() == null ? Maybe.empty() : Maybe.just(current.get())));
        when(store.getHasValidRefresh()).thenReturn(hasToken);
        when(store.saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenAnswer(invocation ->
                Completable.fromAction(() -> {
                    current.set(new Tokens(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
                    hasToken.onNext(true);
                }));
        when(store.clear()).thenAnswer(ignored -> Completable.fromAction(() -> {
            current.set(null);
            hasToken.onNext(false);
        }));
        when(store.commitRefreshRotation(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyString(),
                        any(RecallWindowConfig.class)))
                .thenAnswer(invocation ->
                        Completable.fromAction(() -> {
                            current.set(new Tokens(
                                    invocation.getArgument(2),
                                    invocation.getArgument(3),
                                    invocation.getArgument(4)));
                            hasToken.onNext(true);
                        }));
        when(store.readSessionSnapshot())
                .thenReturn(Single.just(new SessionSnapshot(1L, SessionSnapshot.Lifecycle.ACTIVE)));
        when(store.bootstrapLegacySessionIfNeeded()).thenReturn(Single.just(1L));
        when(store.readPendingRefresh()).thenReturn(Maybe.empty());
        when(store.reservePendingRefresh(anyLong(), anyString(), anyString())).thenReturn(Single.just(true));
        when(store.reserveSessionClosing(anyLong())).thenReturn(Single.just(2L));
        when(store.markSessionClosed(anyLong())).thenReturn(Completable.complete());
        return store;
    }

    private static StompFrame messageFrame() {
        return new StompFrame(StompCommand.MESSAGE, Collections.singletonMap("subscription", "sub-1"),
                "{\"eventType\":\"MESSAGE_CREATED\",\"conversationId\":\"c-1\","
                        + "\"serverTs\":\"2026-11-03T09:12:04Z\",\"payload\":{\"id\":\"m-1\","
                        + "\"seq\":1,\"conversationId\":\"c-1\",\"senderId\":\"u-1\","
                        + "\"type\":\"TEXT\",\"content\":\"hello\",\"createdAt\":\"2026-11-03T09:12:04Z\"}}");
    }
}

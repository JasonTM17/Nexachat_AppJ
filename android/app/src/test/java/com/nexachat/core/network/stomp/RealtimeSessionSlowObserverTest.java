package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.Test;

/** Slow public observers must not hold the realtime session or attempt gates. */
public final class RealtimeSessionSlowObserverTest {

    @Test
    public void slowEventObserverDoesNotBlockOkHttpCallbackOrLogin() throws Exception {
        TestScheduler reconnectScheduler = new TestScheduler();
        BehaviorProcessor<Boolean> tokensPresent = BehaviorProcessor.createDefault(true);
        AtomicReference<Tokens> storedTokens = new AtomicReference<>(
                new Tokens("account-a", Long.MAX_VALUE, "refresh-a"));
        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(tokensPresent);
        when(store.readTokens()).thenAnswer(ignored -> Maybe.fromCallable(storedTokens::get));

        TokenRefresher refresher = new TokenRefresher(store, mock(SessionEvents.class), () -> null);
        refresher.onSessionEstablished(1L);

        StompClient stomp = new StompClient(new OkHttpClient(), store);
        Map<Long, WebSocketListener> listeners = new ConcurrentHashMap<>();
        Map<Long, WebSocket> sockets = new ConcurrentHashMap<>();
        stomp.setTestListenerCapture((listener, attemptId) -> listeners.put(attemptId, listener));
        stomp.setTestWebSocketFactoryForTests((listener, attemptId) -> {
            WebSocket socket = mock(WebSocket.class);
            sockets.put(attemptId, socket);
            return socket;
        });
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        AppForegroundState foreground = () -> Flowable.just(false);
        ExecutorService observerExecutor = Executors.newSingleThreadExecutor();
        Scheduler observerScheduler = Schedulers.from(observerExecutor);
        RealtimeSession session = new RealtimeSession(stomp, store, refresher,
                new Moshi.Builder().build(), new ReconnectPolicy(() -> 0.0), monitor,
                foreground, reconnectScheduler, observerScheduler);
        reconnectScheduler.triggerActions();

        CountDownLatch observerEntered = new CountDownLatch(1);
        CountDownLatch releaseObserver = new CountDownLatch(1);
        CountDownLatch callbackReturned = new CountDownLatch(1);
        CountDownLatch epochAdvanced = new CountDownLatch(1);
        CountDownLatch bothOperationsCompleted = new CountDownLatch(2);
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<Throwable> epochFailure = new AtomicReference<>();
        Disposable observer = session.getEvents().subscribe(event -> {
            observerEntered.countDown();
            try {
                epochAdvanced.await();
                releaseObserver.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                observerFailure.set(interrupted);
            }
        });

        WebSocket socket = sockets.get(1L);
        WebSocketListener listener = listeners.get(1L);
        assertNotNull(socket);
        assertNotNull(listener);
        listener.onOpen(socket, mock(Response.class));
        listener.onMessage(socket, StompCodec.encode(new StompFrame(StompCommand.CONNECTED)));
        String body = "{\"eventType\":\"USER_TYPING\",\"conversationId\":\"1\","
                + "\"payload\":{\"userId\":\"user-a\",\"displayName\":\"A\"}}";
        StompFrame event = new StompFrame(
                StompCommand.MESSAGE, Map.of("destination", "/user/queue/inbox"), body);
        Thread callback = new Thread(() -> {
            try {
                listener.onMessage(socket, StompCodec.encode(event));
            } catch (Throwable failure) {
                callbackFailure.set(failure);
            } finally {
                callbackReturned.countDown();
                bothOperationsCompleted.countDown();
            }
        }, "okhttp-callback-test");
        Thread login = new Thread(() -> {
            try {
                storedTokens.set(new Tokens("account-b", Long.MAX_VALUE, "refresh-b"));
                refresher.onSessionEstablished(2L);
            } catch (Throwable failure) {
                epochFailure.set(failure);
            } finally {
                epochAdvanced.countDown();
                bothOperationsCompleted.countDown();
            }
        }, "login-session-advance-test");

        boolean observerStarted = false;
        boolean completedWhileObserverBlocked = false;
        boolean callbackWasUnblocked = false;
        boolean epochWasUnblocked = false;
        try {
            callback.start();
            observerStarted = observerEntered.await(5L, TimeUnit.SECONDS);
            if (observerStarted) {
                login.start();
                completedWhileObserverBlocked = bothOperationsCompleted.await(5L, TimeUnit.SECONDS);
                callbackWasUnblocked = callbackReturned.getCount() == 0L;
                epochWasUnblocked = epochAdvanced.getCount() == 0L;
            }
        } finally {
            epochAdvanced.countDown();
            releaseObserver.countDown();
            if (callback.isAlive()) callback.join(TimeUnit.SECONDS.toMillis(5L));
            if (login.isAlive()) login.join(TimeUnit.SECONDS.toMillis(5L));
            observer.dispose();
            session.dispose();
            observerExecutor.shutdownNow();
        }

        assertTrue("slow event subscriber should receive the frame", observerStarted);
        assertTrue("OkHttp onMessage should return while downstream is blocked", callbackWasUnblocked);
        assertTrue("login must advance the epoch while the downstream subscriber is blocked",
                epochWasUnblocked && completedWhileObserverBlocked);
        assertNull("test callback should have no failure", callbackFailure.get());
        assertNull("login/session advance should have no failure", epochFailure.get());
        assertNull("subscriber should have no failure", observerFailure.get());
    }
}

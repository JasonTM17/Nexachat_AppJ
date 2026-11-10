package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** CONNECT bearer selection and attempt-generation suppression. */
public final class StompClientBearerAttemptTest {

    @Before
    public void trampolineIo() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
    }

    @After
    public void resetPlugins() {
        RxJavaPlugins.reset();
    }

    @Test
    public void explicitBearerIsObservedWithoutReadingStore() {
        TokenStore store = mock(TokenStore.class);
        when(store.readTokens()).thenReturn(Maybe.error(new AssertionError("store read")));
        StompClient client = new StompClient(new OkHttpClient(), store);
        AtomicReference<String> observed = new AtomicReference<>();
        client.setConnectBearerObserverForTests(observed::set);
        client.connect("attempt-bearer")
                .test()
                .assertValueCount(1)
                .assertValueAt(0, StompClient.State.Connecting.INSTANCE);
        assertEquals("attempt-bearer", observed.get());
    }

    @Test
    public void newerAttemptSuppressesStaleListenerCallbacks() {
        TokenStore store = mock(TokenStore.class);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("first", Long.MAX_VALUE, "r")));
        StompClient client = new StompClient(new OkHttpClient(), store);
        AtomicInteger emissions = new AtomicInteger();
        client.setConnectBearerObserverForTests(ignored -> emissions.incrementAndGet());
        client.connect("first").test();
        assertEquals(1, emissions.get());
        client.connect("second").test();
        assertEquals(2, emissions.get());
    }

    @Test
    public void nullBearerFallsBackToTokenStore() {
        TokenStore store = mock(TokenStore.class);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("from-store", Long.MAX_VALUE, "r")));
        StompClient client = new StompClient(new OkHttpClient(), store);
        AtomicReference<String> observed = new AtomicReference<>();
        client.setConnectBearerObserverForTests(observed::set);
        client.connect().test().awaitCount(1);
        assertEquals("from-store", observed.get());
    }
}

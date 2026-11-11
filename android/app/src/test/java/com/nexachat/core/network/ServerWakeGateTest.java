package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

public final class ServerWakeGateTest {
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void retriesGatewayFailuresWithBoundedBackoffUntilHealthIsReady() throws Exception {
        server.enqueue(response(502));
        server.enqueue(response(503));
        server.enqueue(response(504));
        server.enqueue(response(200));
        TestScheduler clock = new TestScheduler();
        ServerWakeGate gate = gate(clock, 15_000L);
        var states = gate.getIsStarting().test();
        var ready = gate.awaitReady().test();

        assertHealthRequest(server.takeRequest(1, TimeUnit.SECONDS));
        assertTrue(ready.values().isEmpty());
        assertEquals(List.of(false, true), states.values());

        clock.advanceTimeBy(1, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(1, TimeUnit.SECONDS));
        clock.advanceTimeBy(2, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(1, TimeUnit.SECONDS));
        clock.advanceTimeBy(4, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(1, TimeUnit.SECONDS));

        ready.assertValue(true);
        assertEquals(List.of(false, true, false), states.values());
        assertEquals(4, server.getRequestCount());
    }

    @Test
    public void retriesSocketTimeoutThenClearsStartingStateWhenReady() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(response(200));
        TestScheduler clock = new TestScheduler();
        ServerWakeGate gate = gate(clock, 100L);
        var states = gate.getIsStarting().test();
        var ready = gate.awaitReady().test();

        assertHealthRequest(server.takeRequest(1, TimeUnit.SECONDS));
        assertTrue(ready.values().isEmpty());
        assertEquals(List.of(false, true), states.values());

        clock.advanceTimeBy(1, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(1, TimeUnit.SECONDS));
        ready.assertValue(true);
        assertEquals(List.of(false, true, false), states.values());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void stopsStartingRetryAtTheWaitDeadline() throws Exception {
        for (int index = 0; index < 16; index++) {
            server.enqueue(response(503));
        }
        TestScheduler clock = new TestScheduler();
        ServerWakeGate gate = gate(clock, 15_000L);
        var states = gate.getIsStarting().test();
        var ready = gate.awaitReady().test();

        long[] delaysSeconds = {1, 2, 4, 8, 16, 16, 16, 16, 16, 16, 16, 16};
        long elapsedSeconds = 0;
        for (long delaySeconds : delaysSeconds) {
            assertHealthRequest(server.takeRequest(1, TimeUnit.SECONDS));
            clock.advanceTimeBy(delaySeconds, TimeUnit.SECONDS);
            elapsedSeconds += delaySeconds;
        }
        assertHealthRequest(server.takeRequest(1, TimeUnit.SECONDS));
        long remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(ServerWakeGate.MAX_WAIT_MS) - elapsedSeconds;
        clock.advanceTimeBy(remainingSeconds - 1, TimeUnit.SECONDS);
        ready.assertNoValues();
        clock.advanceTimeBy(1, TimeUnit.SECONDS);

        ready.assertValue(false);
        assertEquals(13, server.getRequestCount());
        assertEquals(List.of(false, true, false), states.values());
    }

    private ServerWakeGate gate(TestScheduler clock, long readTimeoutMs) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(new Moshi.Builder().build()))
                .build();
        return new ServerWakeGate(retrofit.create(HealthApi.class), Schedulers.trampoline(), clock,
                () -> clock.now(TimeUnit.MILLISECONDS));
    }

    private static MockResponse response(int status) {
        return new MockResponse().setResponseCode(status).setBody("{\"status\":\"UP\"}");
    }

    private static void assertHealthRequest(RecordedRequest request) {
        assertNotNull("health retry should dispatch an HTTP request", request);
        assertEquals("GET", request.getMethod());
        assertEquals("/actuator/health", request.getPath());
    }
}

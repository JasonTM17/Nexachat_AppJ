package com.nexachat.call.core;

import com.squareup.moshi.Moshi;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CallRepositoryTest {
    private ServerSocket server;
    private ExecutorService serverExecutor;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private CallRepository repository;

    @Before
    public void setUp() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.execute(this::serve);
        Moshi moshi = new Moshi.Builder().build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://127.0.0.1:" + server.getLocalPort() + "/")
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build();
        repository = new CallRepository(retrofit.create(CallRepository.Api.class), moshi);
    }

    @After
    public void tearDown() throws IOException {
        if (server != null) server.close();
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    public void iceConfigUsesTheAuthenticatedRetrofitRouteAndParsesServers() {
        CallRepository.Result<IceConfig> result = repository.getIceConfig();

        assertTrue(result.isSuccess());
        assertEquals(Collections.singletonList("stun:stun.example.test:3478"),
                result.getValue().getIceServers().get(0).getUrls());
        assertEquals(3600, result.getValue().getTtlSeconds());
        assertEquals("GET /api/v1/calls/ice-config", requests.get(0));
    }

    @Test
    public void historyUsesConversationRouteAndBoundsLimit() {
        CallRepository.Result<CallRepository.CallHistoryPage> result =
                repository.getHistory("conversation-1", 200, "cursor-1");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getValue().getItems().size());
        assertEquals("call-1", result.getValue().getItems().get(0).getCallId());
        assertEquals(CallEndReason.COMPLETED,
                result.getValue().getItems().get(0).getEndReason());
        assertEquals("next-1", result.getValue().getNextCursor());
        assertTrue(requests.get(0).startsWith(
                "GET /api/v1/conversations/conversation-1/calls?"));
        assertTrue(requests.get(0).contains("limit=100"));
        assertTrue(requests.get(0).contains("cursor=cursor-1"));
    }

    @Test
    public void notMemberErrorMapsToTypedCallError() {
        CallRepository.Result<CallRepository.CallHistoryPage> result =
                repository.getHistory("denied", 20, null);

        assertTrue(!result.isSuccess());
        assertEquals(CallError.Code.NOT_MEMBER, result.getError().getCode());
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                respond(client);
            } catch (IOException exception) {
                if (!server.isClosed()) throw new IllegalStateException(exception);
            }
        }
    }

    private void respond(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                client.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = reader.readLine();
        if (requestLine == null) return;
        String[] requestParts = requestLine.split(" ");
        String method = requestParts[0];
        String target = requestParts.length > 1 ? requestParts[1] : "/";
        String header;
        while ((header = reader.readLine()) != null && !header.isEmpty()) {
            // GET requests in this contract have no body.
        }
        requests.add(method + " " + target);

        String path = target.split("\\?", 2)[0];
        int status = 200;
        String body;
        if ("/api/v1/calls/ice-config".equals(path)) {
            body = "{\"iceServers\":[{\"urls\":[\"stun:stun.example.test:3478\"]}],"
                    + "\"ttlSeconds\":3600}";
        } else if (path.endsWith("/denied/calls")) {
            status = 403;
            body = "{\"code\":\"NOT_MEMBER\"}";
        } else {
            body = "{\"items\":[{\"callId\":\"call-1\",\"callerId\":\"u-1\","
                    + "\"calleeId\":\"u-2\",\"state\":\"ENDED\","
                    + "\"endReason\":\"COMPLETED\",\"startedAt\":\"2026-11-05T00:00:00Z\","
                    + "\"answeredAt\":\"2026-11-05T00:00:02Z\","
                    + "\"endedAt\":\"2026-11-05T00:00:18Z\",\"durationSeconds\":16}],"
                    + "\"nextCursor\":\"next-1\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : "Forbidden";
        String responseHeaders = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = client.getOutputStream();
        output.write(responseHeaders.getBytes(StandardCharsets.ISO_8859_1));
        output.write(bytes);
        output.flush();
    }
}

package com.nexachat.feature.scheduled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

/**
 * Contract test for the settled scheduled-message REST surface: exact paths,
 * methods, request field names, and response field names.
 */
public final class ScheduledMessageApiContractTest {

    private static final String ROW_JSON =
            "{\"id\":\"s-1\",\"conversationId\":\"c-1\",\"clientMessageId\":\"cm-1\","
                    + "\"body\":\"Xin chào\",\"sendAt\":\"2026-12-01T02:00:00Z\","
                    + "\"status\":\"PENDING\",\"failureCode\":null,\"sentMessageId\":null,"
                    + "\"createdAt\":\"2026-11-30T02:00:00Z\",\"updatedAt\":\"2026-11-30T02:00:00Z\"}";

    private MockWebServer server;
    private ScheduledMessageApi api;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        api = new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(new okhttp3.OkHttpClient())
                .addConverterFactory(MoshiConverterFactory.create(new Moshi.Builder().build()))
                .build()
                .create(ScheduledMessageApi.class);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void listIssuesGetOnTheConversationCollectionAndParsesRows() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[" + ROW_JSON + "]"));

        Response<List<ScheduledMessageDto>> response = api.list("c-1").execute();

        assertEquals(200, response.code());
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/api/v1/conversations/c-1/scheduled-messages", request.getPath());
        ScheduledMessageDto row = response.body().get(0);
        assertEquals("s-1", row.getId());
        assertEquals("c-1", row.getConversationId());
        assertEquals("cm-1", row.getClientMessageId());
        assertEquals("Xin chào", row.getBody());
        assertEquals("2026-12-01T02:00:00Z", row.getSendAt());
        assertEquals("PENDING", row.getStatus());
        assertNull(row.getFailureCode());
        assertNull(row.getSentMessageId());
        assertEquals("2026-11-30T02:00:00Z", row.getCreatedAt());
        assertEquals("2026-11-30T02:00:00Z", row.getUpdatedAt());
    }

    @Test
    public void createIssuesPostWithTheContractFieldNames() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(ROW_JSON));

        Response<ScheduledMessageDto> response = api.create("c-1",
                new ScheduledMessageRequest("cm-9", "Xin chào", "2026-12-01T02:00:00Z"))
                .execute();

        assertEquals(201, response.code());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/v1/conversations/c-1/scheduled-messages", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"clientMessageId\":\"cm-9\""));
        assertTrue(body.contains("\"body\":\"Xin chào\""));
        assertTrue(body.contains("\"sendAt\":\"2026-12-01T02:00:00Z\""));
        assertEquals("s-1", response.body().getId());
    }

    @Test
    public void createAcceptsTheIdempotent200Replay() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(ROW_JSON));

        Response<ScheduledMessageDto> response = api.create("c-1",
                new ScheduledMessageRequest("cm-1", "Xin chào", "2026-12-01T02:00:00Z"))
                .execute();

        assertEquals(200, response.code());
        assertTrue(response.isSuccessful());
        assertEquals("cm-1", response.body().getClientMessageId());
    }

    @Test
    public void deleteIssuesDeleteOnTheRowAndAccepts204() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        Response<Void> response = api.delete("c-1", "s-1").execute();

        assertEquals(204, response.code());
        RecordedRequest request = server.takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("/api/v1/conversations/c-1/scheduled-messages/s-1", request.getPath());
    }

    @Test
    public void terminalRowFieldsRoundTripThroughTheDto() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"id\":\"s-2\",\"conversationId\":\"c-1\","
                        + "\"clientMessageId\":\"cm-2\",\"body\":\"\","
                        + "\"sendAt\":\"2026-12-01T02:00:00Z\",\"status\":\"FAILED\","
                        + "\"failureCode\":\"FAILED_RETRY_EXHAUSTED\","
                        + "\"sentMessageId\":null,\"createdAt\":\"2026-11-30T02:00:00Z\","
                        + "\"updatedAt\":\"2026-12-01T02:00:05Z\"}]"));

        Response<List<ScheduledMessageDto>> response = api.list("c-1").execute();

        ScheduledMessageDto row = response.body().get(0);
        assertEquals("FAILED", row.getStatus());
        assertEquals("FAILED_RETRY_EXHAUSTED", row.getFailureCode());
        assertEquals("", row.getBody());
        assertEquals("2026-12-01T02:00:05Z", row.getUpdatedAt());
    }
}

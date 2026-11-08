package com.nexachat.feature.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import org.junit.Test;

public final class AskModelsTest {
    @Test
    public void parsesNullAnswerAndUsageWithMoshi() throws Exception {
        JsonAdapter<AskResponse> adapter = new Moshi.Builder().build().adapter(AskResponse.class);
        AskResponse response = adapter.fromJson(
                "{\"answer\":null,\"citations\":[],\"mode\":\"NOT_FOUND\","
                        + "\"usage\":{\"llmCalls\":0,\"inputChars\":0,\"cached\":false}}");

        assertNotNull(response);
        assertNull(response.getAnswer());
        assertTrue(response.getCitations().isEmpty());
        assertEquals("NOT_FOUND", response.getMode());
        assertEquals(0, response.getUsage().getLlmCalls());

        AskResponse cited = adapter.fromJson(
                "{\"answer\":\"Friday\",\"citations\":[{\"messageId\":\"message-12\","
                        + "\"seq\":12,\"snippet\":\"Migration starts Friday\"}],"
                        + "\"mode\":\"EXTRACTIVE\",\"usage\":{\"llmCalls\":0,"
                        + "\"inputChars\":0,\"cached\":false}}");
        assertNotNull(cited);
        assertEquals("message-12", cited.getCitations().get(0).getMessageId());
        assertEquals(Long.valueOf(12L), cited.getCitations().get(0).getSeq());
        assertEquals("Migration starts Friday", cited.getCitations().get(0).getSnippet());
    }

    @Test
    public void mapsErrorCodesAndRetryDetails() {
        assertEquals(AskError.CHAT_UNAVAILABLE,
                AskErrorMapper.fromHttp(403, "{\"error\":{\"code\":\"NOT_MEMBER\"}}").getError());
        assertEquals(AskError.CHAT_UNAVAILABLE,
                AskErrorMapper.fromHttp(404,
                        "{\"error\":{\"code\":\"CONVERSATION_NOT_FOUND\"}}").getError());
        assertEquals(AskError.CONSENT_REQUIRED,
                AskErrorMapper.fromHttp(403,
                        "{\"error\":{\"code\":\"CONSENT_REQUIRED\"}}").getError());
        AskErrorMapping rateLimited = AskErrorMapper.fromHttp(429,
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"details\":{\"retryAfterSeconds\":23}}}");
        assertEquals(AskError.RATE_LIMITED, rateLimited.getError());
        assertEquals(Long.valueOf(23L), rateLimited.getRetryAfterSeconds());
    }

    @Test
    public void citationJumpPreservesMessageIdAndSequenceAndRejectsMissingId() {
        AskCitationJump jump = AskCitationJump.from(new AskCitation("message-17", 17L, "Source text"));

        assertNotNull(jump);
        assertEquals("message-17", jump.getMessageId());
        assertEquals(Long.valueOf(17L), jump.getSeq());
        assertNull(AskCitationJump.from(new AskCitation(null, 17L, "Source text")));
    }
}

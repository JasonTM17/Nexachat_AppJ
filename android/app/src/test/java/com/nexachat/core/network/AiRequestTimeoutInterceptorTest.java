package com.nexachat.core.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AiRequestTimeoutInterceptorTest {

    @Test
    public void summaryAndAskPostsWaitForTheServer() {
        assertTrue(AiRequestTimeoutInterceptor.isAiRequest("POST",
                "/api/v1/conversations/7b0c1f5e-1111-2222-3333-444455556666/summary"));
        assertTrue(AiRequestTimeoutInterceptor.isAiRequest("POST",
                "/api/v1/conversations/7b0c1f5e-1111-2222-3333-444455556666/ask"));
    }

    @Test
    public void otherCallsKeepTheDefaultTimeout() {
        assertFalse(AiRequestTimeoutInterceptor.isAiRequest("GET",
                "/api/v1/conversations/7b0c1f5e-1111-2222-3333-444455556666/summary"));
        assertFalse(AiRequestTimeoutInterceptor.isAiRequest("POST",
                "/api/v1/conversations/7b0c1f5e-1111-2222-3333-444455556666/messages"));
        assertFalse(AiRequestTimeoutInterceptor.isAiRequest("POST", "/api/v1/auth/login"));
    }
}
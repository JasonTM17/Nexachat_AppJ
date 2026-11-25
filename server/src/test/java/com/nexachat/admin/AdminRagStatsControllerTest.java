package com.nexachat.admin;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.GlobalExceptionHandler;
import com.nexachat.rag.RagStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Focused web contract for the admin-proxied, count-only RAG stats snapshot. */
class AdminRagStatsControllerTest {

    private static final String STATS_PATH = "/api/v1/admin/rag/stats";

    private RagStats stats;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        stats = new RagStats();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminRagStatsController(stats))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void statsReturnsTheFullCounterSnapshotIncludingBulkheadRejections() throws Exception {
        signInAsModerator();
        stats.recordRequest();
        stats.recordRequest();
        stats.recordCacheHit();
        stats.recordNotFound();
        stats.recordExtractive();
        stats.recordLlmCall(200, true);
        stats.recordLlmFailure();
        stats.recordLlmBulkheadRejection();
        stats.recordEmbeddingCall(3);
        stats.recordEmbeddingFailure();
        stats.recordIndexedMessages(7);

        mockMvc.perform(get(STATS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").value(2))
                .andExpect(jsonPath("$.cacheHits").value(1))
                .andExpect(jsonPath("$.notFound").value(1))
                .andExpect(jsonPath("$.extractive").value(1))
                .andExpect(jsonPath("$.llmCalls").value(1))
                .andExpect(jsonPath("$.llmFallbackCalls").value(1))
                .andExpect(jsonPath("$.llmFailures").value(1))
                .andExpect(jsonPath("$.llmBulkheadRejections").value(1))
                .andExpect(jsonPath("$.inputChars").value(200))
                .andExpect(jsonPath("$.meanInputChars").value(200.0))
                .andExpect(jsonPath("$.embeddingCalls").value(3))
                .andExpect(jsonPath("$.embeddingFailures").value(1))
                .andExpect(jsonPath("$.indexedMessages").value(7));
    }

    @Test
    void statsFailsClosedWithoutAStaffPrincipal() throws Exception {
        mockMvc.perform(get(STATS_PATH))
                .andExpect(status().isUnauthorized());
    }

    private void signInAsModerator() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtPrincipal(UUID.randomUUID()), null,
                List.of(() -> "ROLE_MODERATOR")));
    }
}

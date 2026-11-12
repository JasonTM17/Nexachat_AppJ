package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/rag/stats} is operator telemetry, not user data: it must
 * be reachable only by an authenticated caller whose phone is on the
 * configured admin allow-list.
 */
class RagStatsAuthorizationIT extends SecurityITBase {

    @Test
    void ragStatsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/rag/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void ragStatsIsForbiddenForANonAdminUser() throws Exception {
        Registered user = register(ItPhoneRanges.phoneFor(RagStatsAuthorizationIT.class, "+84905551401"));

        mockMvc.perform(get("/api/v1/rag/stats").header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void ragStatsIsAllowedForTheConfiguredAdmin() throws Exception {
        Registered admin = register(ADMIN_PHONE);

        mockMvc.perform(get("/api/v1/rag/stats").header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").isNumber())
                .andExpect(jsonPath("$.llmCalls").isNumber());
    }
}

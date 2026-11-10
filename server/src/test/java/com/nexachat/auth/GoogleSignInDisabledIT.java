package com.nexachat.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The feature flag defaults to off, and a disabled feature must be
 * indistinguishable from a route that does not exist.
 */
class GoogleSignInDisabledIT extends SecurityITBase {

    @Test
    void everyGoogleEndpointAnswers404WhileDisabled() throws Exception {
        Registered user = register("+84905556011");

        postJson("/api/v1/auth/google/nonce", Map.of())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        postJson("/api/v1/auth/google",
                Map.of("idToken", "irrelevant", "nonce", "irrelevant"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        getWithAuth("/api/v1/auth/identities", user.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        postJson("/api/v1/auth/identities/google",
                Map.of("idToken", "irrelevant", "nonce", "irrelevant", "password", "secret123"),
                user.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/auth/identities/google")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "secret123"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void theTablesExistEvenWhileTheFeatureIsOff() throws Exception {
        // Flyway applies V27 for every deployment; the flag only gates the API.
        Integer identities = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_identities", Integer.class);
        Integer nonces = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM google_signin_nonces", Integer.class);
        org.assertj.core.api.Assertions.assertThat(identities).isNotNull();
        org.assertj.core.api.Assertions.assertThat(nonces).isNotNull();
    }
}

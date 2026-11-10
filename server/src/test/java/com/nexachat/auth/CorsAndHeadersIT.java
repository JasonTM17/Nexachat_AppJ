package com.nexachat.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environment hardening: cross-origin requests are admitted only from the
 * configured allow-list, security headers are pinned, and actuator exposure
 * stays limited to the public health probe.
 */
class CorsAndHeadersIT extends SecurityITBase {

    @Test
    void corsPreflightAdmitsConfiguredOriginOnlyOnPublicDeletionRoutes() throws Exception {
        mockMvc.perform(options("/api/v1/account-deletion/request")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

        mockMvc.perform(options("/api/v1/account-deletion/request")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void corsIsNotEnabledForAuthenticatedApiRoutes() throws Exception {
        Registered user = register("+84905551501");

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        // Other API routes do not emit CORS headers even for a disallowed origin.
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void authResponsesCarryNoStoreAndSecurityHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void protectedResponsesCarryNosniffWithoutCachingSecrets() throws Exception {
        Registered user = register("+84905551502");

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void actuatorExposesHealthOnly() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // Anything else behind the management base path needs a token.
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isUnauthorized());

        Registered user = register("+84905551503");
        // A non-admin user is refused; only operator phones may reach management endpoints.
        mockMvc.perform(get("/actuator/beans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isForbidden());
    }
}

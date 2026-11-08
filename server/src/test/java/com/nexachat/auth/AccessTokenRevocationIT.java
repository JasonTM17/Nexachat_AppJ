package com.nexachat.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access-token revocation: logout, logout-all and password change must stop a
 * previously issued access token from being replayed, while refresh-token
 * rotation keeps working exactly as before.
 */
class AccessTokenRevocationIT extends SecurityITBase {

    @Test
    void logoutInvalidatesTheAccessTokenItWasUsedWith() throws Exception {
        Registered user = register("+84905551201");

        getWithAuth("/api/v1/users/me", user.accessToken())
                .andExpect(status().isOk());

        letTheTokenFallInThePreviousSecond();
        postJson("/api/v1/auth/logout", Map.of("refreshToken", user.refreshToken()),
                user.accessToken())
                .andExpect(status().isNoContent());

        getWithAuth("/api/v1/users/me", user.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void logoutAllInvalidatesEveryAccessTokenOfTheUser() throws Exception {
        Registered user = register("+84905551202");
        Registered secondSession = login("+84905551202");

        letTheTokenFallInThePreviousSecond();
        postJson("/api/v1/auth/logout-all", Map.of(), user.accessToken())
                .andExpect(status().isNoContent());

        getWithAuth("/api/v1/users/me", user.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
        getWithAuth("/api/v1/users/me", secondSession.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void passwordChangeInvalidatesExistingAccessTokensButKeepsRotationAlive() throws Exception {
        Registered user = register("+84905551203");

        letTheTokenFallInThePreviousSecond();
        patchWithAuth("/api/v1/auth/password", Map.of(
                        "oldPassword", "secret123",
                        "newPassword", "newsecret456",
                        "refreshToken", user.refreshToken()),
                user.accessToken())
                .andExpect(status().isNoContent());

        getWithAuth("/api/v1/users/me", user.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));

        // Rotation of the surviving session token still works and its fresh
        // access token is accepted: revocation is a cutoff, not a lockout.
        ResultActions refreshed = postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", user.refreshToken()));
        refreshed.andExpect(status().isOk());
        String rotatedAccessToken = objectMapper.readTree(
                        refreshed.andReturn().getResponse().getContentAsString())
                .get("accessToken").asText();

        getWithAuth("/api/v1/users/me", rotatedAccessToken)
                .andExpect(status().isOk());
    }

    @Test
    void logoutOfOneSessionDoesNotRevokeAnotherSessionsRefreshToken() throws Exception {
        Registered first = register("+84905551204");
        Registered second = login("+84905551204");

        letTheTokenFallInThePreviousSecond();
        postJson("/api/v1/auth/logout", Map.of("refreshToken", first.refreshToken()),
                first.accessToken())
                .andExpect(status().isNoContent());

        // The logged-out session's access token is dead, and so is the other
        // session's until it rotates.
        getWithAuth("/api/v1/users/me", first.accessToken())
                .andExpect(status().isUnauthorized());
        getWithAuth("/api/v1/users/me", second.accessToken())
                .andExpect(status().isUnauthorized());

        // ... but the second session's refresh token is untouched: rotation
        // still works and the fresh access token is accepted.
        ResultActions refreshed = postJson("/api/v1/auth/refresh",
                Map.of("refreshToken", second.refreshToken()));
        refreshed.andExpect(status().isOk());
        String rotatedAccessToken = objectMapper.readTree(
                        refreshed.andReturn().getResponse().getContentAsString())
                .get("accessToken").asText();
        getWithAuth("/api/v1/users/me", rotatedAccessToken)
                .andExpect(status().isOk());

        // Replaying the logged-out token is reuse: the whole family is revoked.
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", first.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REUSED"));
    }

}

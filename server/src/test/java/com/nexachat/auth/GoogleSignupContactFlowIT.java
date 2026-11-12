package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.auth.google.GoogleTestKeys;
import com.nexachat.auth.google.JwksServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Google first sign-in creates a phone-less account, a second Google account
 * adds that person by verified email, and either side can open the direct chat.
 */
class GoogleSignupContactFlowIT extends SecurityITBase {

    private static final JwksServer JWKS = JwksServer.start(List.of(GoogleTestKeys.keyA()));

    @DynamicPropertySource
    static void googleSignupProperties(DynamicPropertyRegistry registry) {
        registry.add("nexachat.google.signin.enabled", () -> "true");
        registry.add("nexachat.google.signin.signup-enabled", () -> "true");
        registry.add("nexachat.google.signin.web-client-id", () -> GoogleTestKeys.AUDIENCE);
        registry.add("nexachat.google.signin.android-client-ids", () -> GoogleTestKeys.ANDROID_CLIENT_ID);
        registry.add("nexachat.google.signin.jwks-uri", JWKS::uri);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @Test
    void googleSignupAddsContactByEmailAndOpensDirectChat() throws Exception {
        Account first = signUp("google-signup-flow-ada", "ada.flow@example.com");
        Account second = signUp("google-signup-flow-bao", "bao.flow@example.com");

        getWithAuth("/api/v1/users/me", first.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.email").value(first.email()))
                .andExpect(jsonPath("$.emailVerified").value(true));

        postJson("/api/v1/contacts", Map.of("email", first.email()), second.accessToken())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactUserId").value(first.userId().toString()))
                .andExpect(jsonPath("$.phone").value(nullValue()));

        String openedBySecond = openDirect(second, first.userId(), 201);
        String openedByFirst = openDirect(first, second.userId(), 200);
        assertThat(openedByFirst).isEqualTo(openedBySecond);
    }

    private Account signUp(String subject, String email) throws Exception {
        String nonce = issueNonce();
        String token = GoogleTestKeys.token(claims -> {
            claims.nonce = nonce;
            claims.subject = subject;
            claims.email = email;
        });
        var result = postJson("/api/v1/auth/google", Map.of("idToken", token, "nonce", nonce))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newAccount").value(true))
                .andExpect(jsonPath("$.user.phone").doesNotExist())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Account(
                UUID.fromString(body.path("user").path("id").asText()),
                body.path("accessToken").asText(),
                email);
    }

    private String openDirect(Account caller, UUID otherUserId, int expectedStatus) throws Exception {
        var result = postJson("/api/v1/conversations/direct",
                        Map.of("userId", otherUserId.toString()), caller.accessToken())
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.type").value("DIRECT"))
                .andExpect(jsonPath("$.members[0].phone").doesNotExist())
                .andExpect(jsonPath("$.members[1].phone").doesNotExist())
                .andExpect(jsonPath("$.id").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    private String issueNonce() throws Exception {
        var result = postJson("/api/v1/auth/google/nonce", Map.of())
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("nonce").asText();
    }

    private record Account(UUID userId, String accessToken, String email) {
    }
}

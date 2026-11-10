package com.nexachat.robustness;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.UUID;

import static com.nexachat.robustness.RobustnessResponseAssert.isClientErrorWithCode;
import static com.nexachat.robustness.RobustnessResponseAssert.isSafeClientResponse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hostile probes on routes not fully exercised in {@link RestInputRobustnessIT}.
 * Auth routes are probed here without product fixes (owned by brief 244).
 *
 * <p>Runs against the shared IT database and truncates {@code users} in
 * {@code @BeforeAll}; must run sequentially with the other robustness suites.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtendedRouteRobustnessIT extends RobustnessITBase {

    private Registered user;
    private UUID conversationId;

    @BeforeAll
    void seed() throws Exception {
        resetSharedFixtureDatabase();
        user = register("+84907400001", "Extended");
        Registered peer = register("+84907400002", "Peer");
        conversationId = openDirect(user, peer.userId()).id();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh"
    })
    void publicAuthEndpointsRejectMalformedBodiesAsClientErrors(String path) throws Exception {
        postRaw(path, "{broken", null).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        postRaw(path, "null", null).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        mockMvc.perform(post(path).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(isClientErrorWithCode("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void protectedAuthEndpointsRequireAuthenticationBeforeParsing() throws Exception {
        postRaw("/api/v1/auth/logout", "{broken", null)
                .andExpect(isClientErrorWithCode("UNAUTHORIZED"));
        postRaw("/api/v1/auth/logout-all", "null", null)
                .andExpect(isClientErrorWithCode("UNAUTHORIZED"));
        mockMvc.perform(patch("/api/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"\",\"newPassword\":\"\"}"))
                .andExpect(isClientErrorWithCode("UNAUTHORIZED"));
    }

    @Test
    void inviteDeviceAndUserSettingsRejectInvalidInput() throws Exception {
        String token = user.accessToken();
        postRaw("/api/v1/conversations/" + conversationId + "/invite-link",
                "{\"maxUses\":\"x\"}", token)
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/invite-links/not-a-token")
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("NOT_FOUND"));
        postRaw("/api/v1/users/device-token", "{\"token\":\"\",\"platform\":\"ANDROID\"}", token)
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        patchJson("/api/v1/users/me", token, "{\"displayName\":\"\"}")
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        patchJson("/api/v1/users/me/settings", token, "{\"locale\":\"xx-YY\"}")
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
    }

    @Test
    void groupMutationEndpointsRejectInvalidPayloads() throws Exception {
        String token = user.accessToken();
        postRaw("/api/v1/conversations/group",
                "{\"title\":\"\",\"memberIds\":[]}", token)
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        postRaw("/api/v1/conversations/" + conversationId + "/members",
                "{\"userId\":\"bad\"}", token)
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        putJson("/api/v1/conversations/" + conversationId + "/mute", token, "{}")
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
    }

    /**
     * The archive, pin and unmute verbs take no body and are idempotent: the
     * hostile-input contract here is "the documented no-body success", not a
     * rejection.
     */
    @Test
    void memberFlagMutationsWithoutBodyKeepTheirDocumentedSuccess() throws Exception {
        String token = user.accessToken();
        putJson("/api/v1/conversations/" + conversationId + "/archive", token, "{}")
                .andExpect(status().isNoContent());
        putJson("/api/v1/conversations/" + conversationId + "/pin", token, "{}")
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/conversations/" + conversationId + "/mute")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void reactionSyncSummaryAndAskRejectGarbage() throws Exception {
        String token = user.accessToken();
        UUID messageId = sendText(user, conversationId, "target");
        postRaw("/api/v1/messages/" + messageId + "/reactions", "{}", token)
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        postRaw("/api/v1/sync", "{\"watermarks\":null}", token)
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        postRaw("/api/v1/conversations/" + conversationId + "/summary", "{}", token)
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        postRaw("/api/v1/conversations/" + conversationId + "/ask",
                "{\"question\":\"\",\"language\":\"AUTO\"}", token)
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/calls/" + UUID.randomUUID() + "/offer")
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("CALL_NOT_FOUND"));
    }

    @Test
    void validIdempotentMutationsStillSucceed() throws Exception {
        String token = user.accessToken();
        UUID messageId = sendText(user, conversationId, "reaction target");
        mockMvc.perform(put("/api/v1/messages/" + messageId + "/reactions/\uD83D\uDE00")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/calls")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(isSafeClientResponse());
    }

    private org.springframework.test.web.servlet.ResultActions patchJson(
            String path, String token, String json) throws Exception {
        return mockMvc.perform(patch(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private org.springframework.test.web.servlet.ResultActions putJson(
            String path, String token, String json) throws Exception {
        return mockMvc.perform(put(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}

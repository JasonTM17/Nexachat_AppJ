package com.nexachat.robustness;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static com.nexachat.robustness.RobustnessResponseAssert.isSafeClientResponse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Hostile probes on routes not fully exercised in {@link RestInputRobustnessIT}.
 * Auth routes are probed here without product fixes (owned by brief 244).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtendedRouteRobustnessIT extends RobustnessITBase {

    private Registered user;
    private UUID conversationId;

    @BeforeAll
    void seed() throws Exception {
        jdbcTemplate.update("TRUNCATE users CASCADE");
        user = register("+84907400001", "Extended");
        Registered peer = register("+84907400002", "Peer");
        conversationId = openDirect(user, peer.userId()).id();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/logout-all",
            "/api/v1/auth/password"
    })
    void authEndpointsRejectMalformedBodiesWithout5xx(String path) throws Exception {
        postRaw(path, "{broken", null).andExpect(isSafeClientResponse());
        postRaw(path, "null", null).andExpect(isSafeClientResponse());
        mockMvc.perform(post(path).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(isSafeClientResponse());
    }

    @Test
    void inviteGroupDeviceAndUserSettingsSurviveHostileInput() throws Exception {
        String token = user.accessToken();
        postRaw("/api/v1/conversations/" + conversationId + "/invite-link",
                "{\"maxUses\":\"x\"}", token).andExpect(isSafeClientResponse());
        mockMvc.perform(get("/api/v1/invite-links/not-a-token")
                        .header("Authorization", "Bearer " + token))
                .andExpect(isSafeClientResponse());
        postRaw("/api/v1/users/device-token", "{\"token\":\"\",\"platform\":\"ANDROID\"}", token)
                .andExpect(isSafeClientResponse());
        patchJson("/api/v1/users/me", token, "{\"displayName\":\"\"}").andExpect(isSafeClientResponse());
        patchJson("/api/v1/users/me/settings", token, "{\"locale\":\"xx-YY\"}").andExpect(isSafeClientResponse());
    }

    @Test
    void groupMutationEndpointsRejectInvalidPayloads() throws Exception {
        String token = user.accessToken();
        postRaw("/api/v1/conversations/group",
                "{\"title\":\"\",\"memberIds\":[]}", token).andExpect(isSafeClientResponse());
        postRaw("/api/v1/conversations/" + conversationId + "/members",
                "{\"userId\":\"bad\"}", token).andExpect(isSafeClientResponse());
        putJson("/api/v1/conversations/" + conversationId + "/mute", token, "{}").andExpect(isSafeClientResponse());
        putJson("/api/v1/conversations/" + conversationId + "/archive", token, "{}").andExpect(isSafeClientResponse());
        putJson("/api/v1/conversations/" + conversationId + "/pin", token, "{}").andExpect(isSafeClientResponse());
        deleteAuth("/api/v1/conversations/" + conversationId + "/mute", token).andExpect(isSafeClientResponse());
    }

    @Test
    void reactionSyncSummaryAskRejectGarbage() throws Exception {
        String token = user.accessToken();
        UUID messageId = sendText(user, conversationId, "target");
        postRaw("/api/v1/messages/" + messageId + "/reactions", "{}", token).andExpect(isSafeClientResponse());
        mockMvc.perform(put("/api/v1/messages/" + messageId + "/reactions/\uD83D\uDE00")
                        .header("Authorization", "Bearer " + token))
                .andExpect(isSafeClientResponse());
        postRaw("/api/v1/sync", "{\"watermarks\":null}", token).andExpect(isSafeClientResponse());
        postRaw("/api/v1/conversations/" + conversationId + "/summary", "{}", token)
                .andExpect(isSafeClientResponse());
        postRaw("/api/v1/conversations/" + conversationId + "/ask",
                "{\"question\":\"\",\"language\":\"AUTO\"}", token).andExpect(isSafeClientResponse());
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/calls")
                        .header("Authorization", "Bearer " + token))
                .andExpect(isSafeClientResponse());
        mockMvc.perform(get("/api/v1/calls/" + UUID.randomUUID() + "/offer")
                        .header("Authorization", "Bearer " + token))
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

    private org.springframework.test.web.servlet.ResultActions deleteAuth(String path, String token) throws Exception {
        return mockMvc.perform(delete(path).header("Authorization", "Bearer " + token))
                .andExpect(isSafeClientResponse());
    }
}

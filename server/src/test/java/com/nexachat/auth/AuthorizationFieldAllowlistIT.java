package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JSON property allowlists for user, member, message, device-token and invite DTOs. */
class AuthorizationFieldAllowlistIT extends SecurityITBase {

    @Test
    void mainDtoResponsesContainOnlyContractFieldsAndCallerScopedSecrets() throws Exception {
        Registered owner = register("+84905551701");
        Registered member = register("+84905551702");
        Registered requester = register("+84905551703");
        String memberPhone = "+84905551702";

        JsonNode profile = objectMapper.readTree(getWithAuth("/api/v1/users/me", owner.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertKeys(profile, Set.of("id", "phone", "displayName", "avatarUrl", "deleted"));

        MvcResult groupResult = postJson("/api/v1/conversations/group",
                        Map.of("title", "Field allowlist group", "memberIds", List.of(member.userId().toString())),
                        owner.accessToken())
                .andExpect(status().isCreated()).andReturn();
        JsonNode group = objectMapper.readTree(groupResult.getResponse().getContentAsString());
        assertKeys(group, Set.of("id", "type", "members", "createdAt", "aiOptOut"));
        for (JsonNode groupMember : group.path("members")) {
            assertKeys(groupMember, Set.of("userId", "displayName", "phone", "deleted"));
        }
        UUID groupId = UUID.fromString(group.path("id").asText());

        JsonNode message = objectMapper.readTree(postJson(
                        "/api/v1/conversations/" + groupId + "/messages",
                        Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT",
                                "content", "field allowlist", "mentions", List.of(), "mentionAll", false),
                        owner.accessToken())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertKeys(message, Set.of("id", "conversationId", "seq", "senderId", "senderDisplayName", "type",
                "content", "clientMessageId", "replyToId", "replyTo", "attachments", "reactions",
                "createdAt", "editedAt", "deletedAt", "mentions", "mentionAll"));
        assertNoSensitiveOtherUserFields(message);

        JsonNode inbox = objectMapper.readTree(getWithAuth("/api/v1/conversations", owner.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode inboxGroup = findById(inbox, groupId);
        assertKeys(inboxGroup, Set.of("id", "type", "title", "avatarUrl", "members", "lastMessage",
                "unreadCount", "lastReadSeq", "peerPresence", "muted", "mutedUntil", "archived",
                "aiOptOut", "pinned", "pinnedAt", "labels"));
        for (JsonNode inboxMember : inboxGroup.path("members")) {
            assertKeys(inboxMember, Set.of("userId", "displayName", "phone", "avatarUrl", "role"));
        }
        assertNoSensitiveOtherUserFields(inboxGroup);

        JsonNode contact = objectMapper.readTree(postJson("/api/v1/contacts",
                        Map.of("phone", memberPhone), owner.accessToken())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertKeys(contact, Set.of("contactUserId", "phone", "displayName", "aliasName", "avatarUrl", "createdAt"));

        String deviceToken = "test-only-device-token-owner-scope";
        JsonNode device = objectMapper.readTree(postJson("/api/v1/devices/token",
                        Map.of("token", deviceToken, "platform", "ANDROID", "deviceLabel", "owner handset"),
                        owner.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertKeys(device, Set.of("id", "token", "platform", "deviceLabel", "updatedAt"));
        assertThat(device.path("token").asText()).isEqualTo(deviceToken);

        JsonNode createdInvite = objectMapper.readTree(postJson(
                        "/api/v1/conversations/" + groupId + "/invite-link",
                        Map.of("requiresApproval", true), owner.accessToken())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertKeys(createdInvite, Set.of("id", "conversationId", "token", "requiresApproval",
                "expiresAt", "maxUses", "useCount", "createdAt"));
        String inviteToken = createdInvite.path("token").asText();
        assertThat(inviteToken).isNotBlank();

        JsonNode activeInvite = objectMapper.readTree(getWithAuth(
                        "/api/v1/conversations/" + groupId + "/invite-link", owner.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertKeys(activeInvite, Set.of("id", "conversationId", "token", "requiresApproval",
                "expiresAt", "maxUses", "useCount", "createdAt"));
        assertThat(activeInvite.path("token").isTextual()).isFalse();

        postWithoutBody("/api/v1/invite-links/" + inviteToken + "/join", requester.accessToken())
                .andExpect(status().isCreated());
        JsonNode requests = objectMapper.readTree(getWithAuth(
                        "/api/v1/conversations/" + groupId + "/join-requests", owner.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode joinRequest = requests.get(0);
        assertKeys(joinRequest, Set.of("id", "conversationId", "user", "status", "createdAt", "reviewedAt", "reviewedBy"));
        assertKeys(joinRequest.path("user"), Set.of("id", "displayName", "avatarUrl"));
        assertNoSensitiveOtherUserFields(joinRequest);
    }

    @Override
    protected ResultActions postJson(String path, Object body, String accessToken) throws Exception {
        return mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions postWithoutBody(String path, String accessToken)
            throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private static JsonNode findById(JsonNode array, UUID id) {
        for (JsonNode value : array) {
            if (id.toString().equals(value.path("id").asText())) {
                return value;
            }
        }
        throw new AssertionError("Expected conversation missing from caller inbox");
    }

    private static void assertKeys(JsonNode node, Set<String> allowed) {
        assertThat(node.isObject()).isTrue();
        Set<String> actual = new java.util.HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        assertThat(allowed.containsAll(actual))
                .as("JSON fields must be contained in the allowlist")
                .isTrue();
    }

    private static void assertNoSensitiveOtherUserFields(JsonNode node) {
        Set<String> forbidden = Set.of("email", "password", "passwordHash", "deviceToken", "deviceTokens",
                "tokenHash", "inviteTokenHash", "refreshToken", "readReceipts");
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                assertThat(forbidden).as("sensitive JSON field").doesNotContain(field);
                assertNoSensitiveOtherUserFields(node.get(field));
            }
        } else if (node.isArray()) {
            node.forEach(AuthorizationFieldAllowlistIT::assertNoSensitiveOtherUserFields);
        }
    }
}

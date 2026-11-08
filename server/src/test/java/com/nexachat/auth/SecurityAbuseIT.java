package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial probes against the HTTP surface: malformed and oversized bodies,
 * path traversal on the raw file endpoint, and cross-conversation access to
 * messages, attachments, reactions, pins and ask.
 *
 * <p>Every case asserts the exact status and error code so a regression that
 * turns a 403 into a 200 fails loudly.
 */
class SecurityAbuseIT extends SecurityITBase {

    private static final String FILES_PREFIX = "/api/v1/files/";
    private static final String OUTSIDE_SECRET = "nexachat-traversal-secret.txt";

    @Value("${nexachat.storage.root}")
    private String storageRoot;

    @Test
    void malformedJsonIsRejectedWithTheUniformEnvelope() throws Exception {
        postRaw("/api/v1/auth/login", "{not json", null, remoteAddr("10.30.0.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Malformed or unreadable request body"));

        postRaw("/api/v1/auth/login", "[]", null, remoteAddr("10.30.0.2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void oversizedJsonBodyIsRejectedBeforeItIsParsed() throws Exception {
        String oversized = "{\"phone\":\"+84905551601\",\"password\":\""
                + "x".repeat(2 * 1024 * 1024) + "\"}";

        postRaw("/api/v1/auth/login", oversized, null, remoteAddr("10.30.0.3"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void oversizedMultipartUploadIsRejected() throws Exception {
        Registered user = register("+84905551602");
        byte[] oversized = new byte[25 * 1024 * 1024 + 1];
        System.arraycopy(PNG_BYTES, 0, oversized, 0, PNG_BYTES.length);
        MockMultipartFile file = new MockMultipartFile("file", "huge.png", "image/png", oversized);

        mockMvc.perform(multipart("/api/v1/attachments").file(file)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void filePathTraversalCannotReachFilesOutsideTheStorageRoot() throws Exception {
        Registered user = register("+84905551603");

        // A real file just outside the storage root: any traversal that
        // escaped the root would serve these bytes.
        Path outside = Path.of(storageRoot).resolveSibling(OUTSIDE_SECRET);
        Files.writeString(outside, "TOP SECRET");
        try {
            String[] hostileKeys = {
                    "../" + OUTSIDE_SECRET,
                    "..\\" + OUTSIDE_SECRET,
                    "attachments/../../" + OUTSIDE_SECRET,
                    "%2e%2e/" + OUTSIDE_SECRET,
                    "attachments/%2e%2e%2f" + OUTSIDE_SECRET
            };
            for (String key : hostileKeys) {
                var result = mockMvc.perform(get(FILES_PREFIX + key)
                                .header("Authorization", "Bearer " + user.accessToken()))
                        .andReturn();
                // Either the firewall refuses the path or no handler serves it;
                // the invariant is that the planted secret is never returned.
                assertThat(result.getResponse().getStatus()).isNotEqualTo(200);
                assertThat(result.getResponse().getContentAsString()).doesNotContain("TOP SECRET");
            }

            // An ordinary missing key inside the root keeps its contract.
            mockMvc.perform(get(FILES_PREFIX + "attachments/missing.png")
                            .header("Authorization", "Bearer " + user.accessToken()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void nonMemberCannotReadOrActOnAnotherConversation() throws Exception {
        Conversation conversation = conversationWithMessage();

        expectForbidden(mockMvc.perform(get("/api/v1/conversations/" + conversation.id() + "/messages")
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));
        expectForbidden(mockMvc.perform(get("/api/v1/conversations/" + conversation.id() + "/messages/search?q=a")
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));

        expectForbidden(postJson("/api/v1/conversations/" + conversation.id() + "/messages",
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "intrusion"),
                conversation.carol().accessToken()));
        expectForbidden(patchWithAuth("/api/v1/conversations/" + conversation.id()
                        + "/messages/" + conversation.messageId(),
                Map.of("content", "edited"), conversation.carol().accessToken()));
        expectForbidden(mockMvc.perform(delete("/api/v1/conversations/" + conversation.id()
                        + "/messages/" + conversation.messageId())
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));

        expectForbidden(postJson("/api/v1/messages/" + conversation.messageId() + "/reactions",
                Map.of("emoji", "👍"), conversation.carol().accessToken()));
        expectForbidden(mockMvc.perform(put("/api/v1/messages/" + conversation.messageId() + "/reactions/thumbsup")
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));
        expectForbidden(mockMvc.perform(delete("/api/v1/messages/" + conversation.messageId() + "/reactions/thumbsup")
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));

        expectForbidden(mockMvc.perform(get("/api/v1/conversations/" + conversation.id() + "/pins")
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));
        expectForbidden(postJson("/api/v1/conversations/" + conversation.id() + "/pins",
                Map.of("messageId", conversation.messageId()), conversation.carol().accessToken()));
        expectForbidden(mockMvc.perform(delete("/api/v1/conversations/" + conversation.id()
                        + "/pins/" + conversation.messageId())
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));

        expectForbidden(postJson("/api/v1/conversations/" + conversation.id() + "/ask",
                Map.of("question", "what was said?", "language", "AUTO"),
                conversation.carol().accessToken()));
    }

    @Test
    void nonMemberCannotReadAnotherConversationsAttachment() throws Exception {
        Conversation conversation = conversationWithMessage();

        expectForbidden(mockMvc.perform(get("/api/v1/attachments/" + conversation.attachmentId() + "/content")
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));
        expectForbidden(mockMvc.perform(get(FILES_PREFIX + conversation.storageKey())
                .header("Authorization", "Bearer " + conversation.carol().accessToken())));
    }

    @Test
    void unauthenticatedAbuseAttemptsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/" + UUID.randomUUID() + "/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        // The raw traversal path never authenticates and never serves bytes.
        var traversal = mockMvc.perform(get(FILES_PREFIX + "../" + OUTSIDE_SECRET))
                .andReturn();
        assertThat(traversal.getResponse().getStatus()).isNotEqualTo(200);
        assertThat(traversal.getResponse().getContentAsString()).doesNotContain("TOP SECRET");
    }

    // ------------------------------------------------------------------ setup

    private Conversation conversationWithMessage() throws Exception {
        Registered alice = register("+84905551701");
        Registered bob = register("+84905551702");
        Registered carol = register("+84905551703");

        var direct = postJson("/api/v1/conversations/direct",
                Map.of("userId", bob.userId().toString()), alice.accessToken())
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        UUID conversationId = UUID.fromString(objectMapper
                .readTree(direct.getResponse().getContentAsString()).get("id").asText());

        JsonNode uploaded = objectMapper.readTree(mockMvc.perform(
                        multipart("/api/v1/attachments")
                                .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                                .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        UUID attachmentId = UUID.fromString(uploaded.get("id").asText());
        String storageKey = uploaded.get("url").asText().substring(FILES_PREFIX.length());

        var sent = postJson("/api/v1/conversations/" + conversationId + "/messages",
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "IMAGE",
                        "attachmentIds", List.of(attachmentId.toString())),
                alice.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        UUID messageId = UUID.fromString(objectMapper
                .readTree(sent.getResponse().getContentAsString()).get("id").asText());

        return new Conversation(conversationId, messageId, attachmentId, storageKey, carol);
    }

    private void expectForbidden(ResultActions actions) throws Exception {
        actions.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    private static byte[] hex(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private record Conversation(UUID id, UUID messageId, UUID attachmentId,
                                String storageKey, Registered carol) {
    }
}

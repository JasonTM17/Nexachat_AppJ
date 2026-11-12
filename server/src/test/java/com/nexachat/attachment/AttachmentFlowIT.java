package com.nexachat.attachment;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the phase-09 attachment slice against a real
 * PostgreSQL — same env-JDBC pattern as {@code MessageFlowIT} — plus a real
 * filesystem storage root under a per-run temp directory.
 *
 * <p>Covers the whole contract surface: multipart upload (201 + metadata),
 * send-binding via {@code attachmentIds} on an IMAGE message, the
 * member-checked content endpoint (member 200 / non-member 403 /
 * owner-only unbound), the equivalent authZ on the raw
 * {@code /api/v1/files/**} path (the FileController gap fix), the magic-byte
 * whitelist (.exe-renamed-.png → 415), and the 25MB ceiling (→ 413).
 *
 * <p>{@code PER_CLASS}: the three users are registered once — the auth
 * register bucket (10 / 5 min / IP) is shared across the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttachmentFlowIT {

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(30_000);

    /** Minimal PNG (signature + IHDR start) — passes the magic-byte whitelist. */
    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    /** PE/.exe header — the classic disguised-executable probe. */
    private static final byte[] EXE_BYTES = hex("4D5A90000300000004000000FFFF0000");

    private static final Path STORAGE_ROOT = createTempStorageRoot();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        // Real filesystem storage, sandboxed to a per-run temp dir.
        registry.add("nexachat.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempStorageRoot() {
        try {
            return Files.createTempDirectory("nexachat-attachment-it-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol; // never joins the conversation — the non-member probe

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, AttachmentFlowIT.class);
        String tag = "ITA" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser("Zz" + tag + " Carol");
    }

    private static String randomPhone() {
        return ItPhoneRanges.nextPhone(AttachmentFlowIT.class);
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = randomPhone();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(
                UUID.fromString(body.get("user").get("id").asText()),
                phone, displayName, body.get("accessToken").asText());
    }

    private ResultActions postJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UUID openDirectConversation() throws Exception {
        MvcResult result = postJson("/api/v1/conversations/direct", alice.accessToken(),
                Map.of("userId", bob.id().toString()))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private JsonNode upload(TestUser uploader, String filename, String declaredType, byte[] bytes)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, declaredType, bytes);
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(file)
                        .param("width", "16")
                        .param("height", "16")
                        .header("Authorization", "Bearer " + uploader.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static byte[] hex(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private JsonNode uploadWithBlurHash(TestUser uploader, String filename, String declaredType,
                                        byte[] bytes, String blurHash) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, declaredType, bytes);
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder builder =
                multipart("/api/v1/attachments").file(file);
        if (blurHash != null) {
            builder.param("blurHash", blurHash);
        }
        MvcResult result = mockMvc.perform(builder
                        .header("Authorization", "Bearer " + uploader.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ======================================================== blurHash

    @Test
    void blurHashRoundTripsThroughUploadSendAndHistory() throws Exception {
        UUID conversationId = openDirectConversation();
        String blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj";

        JsonNode uploaded = uploadWithBlurHash(
                alice, "photo.png", "image/png", PNG_BYTES, blurHash);
        UUID attachmentId = UUID.fromString(uploaded.get("id").asText());
        assertThat(uploaded.get("blurHash").asText()).isEqualTo(blurHash);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT blur_hash FROM message_attachments WHERE id = ?",
                String.class, attachmentId)).isEqualTo(blurHash);

        MvcResult sent = postJson("/api/v1/conversations/" + conversationId + "/messages",
                alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "IMAGE",
                        "attachmentIds", List.of(attachmentId.toString())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode embedded = objectMapper.readTree(sent.getResponse().getContentAsString())
                .get("attachments").get(0);
        assertThat(embedded.get("blurHash").asText()).isEqualTo(blurHash);

        MvcResult history = mockMvc.perform(get(
                        "/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode historyItem = null;
        for (JsonNode item : objectMapper.readTree(history.getResponse().getContentAsString()).get("items")) {
            if (item.get("attachments").size() == 1
                    && item.get("attachments").get(0).get("id").asText().equals(attachmentId.toString())) {
                historyItem = item;
                break;
            }
        }
        assertThat(historyItem).isNotNull();
        assertThat(historyItem.get("attachments").get(0).get("blurHash").asText())
                .isEqualTo(blurHash);

        // A legacy row without a hash reports null, and the field is omitted on the wire.
        JsonNode legacyUpload = upload(alice, "legacy.png", "image/png", PNG_BYTES);
        assertThat(legacyUpload.has("blurHash")).isFalse();
    }

    @Test
    void invalidBlurHashIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);
        for (String bad : new String[] {"ABCDE", "A".repeat(65), "bad!hash"}) {
            mockMvc.perform(multipart("/api/v1/attachments")
                            .file(file)
                            .param("blurHash", bad)
                            .header("Authorization", "Bearer " + alice.accessToken()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void blurHashOnNonImageIsRejected() throws Exception {
        byte[] pdfBytes = hex("255044462D312E340A25");
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", pdfBytes);
        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(file)
                        .param("blurHash", "LEHV6nWB2yk8pyo0adR*.7kCMdnj")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // =========================================================== ranges

    @Test
    void rangeDownloadsHonourByteRanges() throws Exception {
        UUID conversationId = openDirectConversation();
        JsonNode uploaded = upload(alice, "photo.png", "image/png", PNG_BYTES);
        UUID attachmentId = UUID.fromString(uploaded.get("id").asText());
        postJson("/api/v1/conversations/" + conversationId + "/messages",
                alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "IMAGE",
                        "attachmentIds", List.of(attachmentId.toString())))
                .andExpect(status().isCreated());
        String filesUrl = uploaded.get("url").asText();

        // 200 with no Range: full bytes + Accept-Ranges + Content-Length.
        mockMvc.perform(get(filesUrl).header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().longValue("Content-Length", PNG_BYTES.length))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline"));

        // 206 first bytes.
        MvcResult partial = mockMvc.perform(get(filesUrl)
                        .header("Authorization", "Bearer " + bob.accessToken())
                        .header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-3/" + PNG_BYTES.length))
                .andExpect(header().longValue("Content-Length", 4))
                .andReturn();
        assertThat(partial.getResponse().getContentAsByteArray())
                .containsExactly(java.util.Arrays.copyOf(PNG_BYTES, 4));

        // 206 open-ended.
        MvcResult tail = mockMvc.perform(get(filesUrl)
                        .header("Authorization", "Bearer " + bob.accessToken())
                        .header("Range", "bytes=-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range",
                        "bytes " + (PNG_BYTES.length - 4) + "-" + (PNG_BYTES.length - 1)
                                + "/" + PNG_BYTES.length))
                .andReturn();
        assertThat(tail.getResponse().getContentAsByteArray())
                .containsExactly(java.util.Arrays.copyOfRange(
                        PNG_BYTES, PNG_BYTES.length - 4, PNG_BYTES.length));

        // 416 unsatisfiable.
        mockMvc.perform(get(filesUrl)
                        .header("Authorization", "Bearer " + bob.accessToken())
                        .header("Range", "bytes=999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + PNG_BYTES.length));

        // HEAD works and reports the full length without a body.
        mockMvc.perform(head(filesUrl).header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().longValue("Content-Length", PNG_BYTES.length));
    }

    @Test
    void deletedMessageAttachment_stillHiddenOnRangeRequests() throws Exception {
        UUID conversationId = openDirectConversation();
        JsonNode uploaded = upload(alice, "gone.png", "image/png", PNG_BYTES);
        UUID attachmentId = UUID.fromString(uploaded.get("id").asText());
        String filesUrl = uploaded.get("url").asText();
        MvcResult sent = postJson("/api/v1/conversations/" + conversationId + "/messages",
                alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "IMAGE",
                        "attachmentIds", List.of(attachmentId.toString())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID messageId = UUID.fromString(
                objectMapper.readTree(sent.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}/messages/{messageId}",
                        conversationId, messageId)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());

        for (String range : new String[] {null, "bytes=0-3", "bytes=-4"}) {
            var request = get(filesUrl).header("Authorization", "Bearer " + bob.accessToken());
            if (range != null) {
                request = request.header("Range", range);
            }
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_FOUND"));
        }
    }

    // ==================================================== ISO-BMFF MIME

    @Test
    void voiceNoteWithMoovAtEndIsStoredAndServedAsAudioMp4() throws Exception {
        byte[] voice = resource("voice_aac_isom_moov_end.m4a");
        // Declared as video/mp4 on purpose: the sniff must win.
        JsonNode uploaded = upload(alice, "voice.m4a", "video/mp4", voice);
        assertThat(uploaded.get("mimeType").asText()).isEqualTo("audio/mp4");
        assertThat(uploaded.get("url").asText()).endsWith(".m4a");
        UUID attachmentId = UUID.fromString(uploaded.get("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mime_type FROM message_attachments WHERE id = ?",
                String.class, attachmentId)).isEqualTo("audio/mp4");

        mockMvc.perform(get(uploaded.get("url").asText())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mp4"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mockMvc.perform(get("/api/v1/attachments/" + attachmentId + "/content")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mp4"));
    }

    @Test
    void voiceNoteWithMoovAtFrontIsStoredAndServedAsAudioMp4() throws Exception {
        byte[] voice = resource("voice_aac_isom_faststart.m4a");
        JsonNode uploaded = upload(alice, "voice-fast.m4a", "video/mp4", voice);
        assertThat(uploaded.get("mimeType").asText()).isEqualTo("audio/mp4");
        mockMvc.perform(get(uploaded.get("url").asText())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mp4"));
    }

    @Test
    void h264AacVideoIsStoredAndServedAsVideoMp4() throws Exception {
        byte[] video = resource("video_h264_aac_isom.mp4");
        JsonNode uploaded = upload(alice, "clip.mp4", "video/mp4", video);
        assertThat(uploaded.get("mimeType").asText()).isEqualTo("video/mp4");
        mockMvc.perform(get(uploaded.get("url").asText())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = AttachmentFlowIT.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture: " + name);
            }
            return in.readAllBytes();
        }
    }

    // ================================================================ flow

    @Test
    void uploadBindAndMemberCheckedDownload() throws Exception {
        UUID conversationId = openDirectConversation();

        // --- upload: multipart png → 201 + canonical metadata ---
        JsonNode uploaded = upload(alice, "photo.png", "image/png", PNG_BYTES);
        UUID attachmentId = UUID.fromString(uploaded.get("id").asText());
        assertThat(uploaded.get("mimeType").asText()).isEqualTo("image/png");
        assertThat(uploaded.get("originalName").asText()).isEqualTo("photo.png");
        assertThat(uploaded.get("sizeBytes").asLong()).isEqualTo(PNG_BYTES.length);
        assertThat(uploaded.get("url").asText()).startsWith("/api/v1/files/attachments/").endsWith(".png");

        // storage_key is server-minted — the client filename is not a path.
        String storageKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM message_attachments WHERE id = ?",
                String.class, attachmentId);
        assertThat(storageKey).doesNotContain("photo.png").doesNotContain("..");
        assertThat(STORAGE_ROOT.resolve(storageKey)).exists().hasBinaryContent(PNG_BYTES);
        // The row is unbound and owned by alice.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_id FROM message_attachments WHERE id = ?", UUID.class, attachmentId))
                .isEqualTo(alice.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_id FROM message_attachments WHERE id = ?", UUID.class, attachmentId))
                .isNull();

        // --- unbound upload is owner-only ---
        mockMvc.perform(get("/api/v1/attachments/" + attachmentId + "/content")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attachments/" + attachmentId + "/content")
                        .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        // --- send type=IMAGE with attachmentIds → bound in the same tx ---
        MvcResult sent = postJson("/api/v1/conversations/" + conversationId + "/messages",
                alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "IMAGE",
                        "attachmentIds", List.of(attachmentId.toString())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode message = objectMapper.readTree(sent.getResponse().getContentAsString());
        assertThat(message.get("type").asText()).isEqualTo("IMAGE");
        assertThat(message.get("attachments")).hasSize(1);
        JsonNode embedded = message.get("attachments").get(0);
        assertThat(embedded.get("id").asText()).isEqualTo(attachmentId.toString());
        assertThat(embedded.get("mimeType").asText()).isEqualTo("image/png");
        assertThat(embedded.get("originalName").asText()).isEqualTo("photo.png");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_id FROM message_attachments WHERE id = ?", UUID.class, attachmentId))
                .isEqualTo(UUID.fromString(message.get("id").asText()));

        // --- GET /attachments/{id}/content: peer member 200, bytes intact ---
        MvcResult content = mockMvc.perform(get("/api/v1/attachments/" + attachmentId + "/content")
                        .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("inline")))
                .andReturn();
        assertThat(content.getResponse().getContentAsByteArray()).containsExactly(PNG_BYTES);

        // non-member → 403 NOT_MEMBER on the id endpoint…
        mockMvc.perform(get("/api/v1/attachments/" + attachmentId + "/content")
                        .header("Authorization", "Bearer " + carol.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        // …and on the raw files path — the FileController authZ gap is closed.
        String filesUrl = uploaded.get("url").asText();
        mockMvc.perform(get(filesUrl).header("Authorization", "Bearer " + carol.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        mockMvc.perform(get(filesUrl).header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk());

        // unknown id → 404 ATTACHMENT_NOT_FOUND.
        mockMvc.perform(get("/api/v1/attachments/" + UUID.randomUUID() + "/content")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void deletingMediaMessageKeepsAttachmentOutOfTombstoneHistory() throws Exception {
        UUID conversationId = openDirectConversation();
        JsonNode uploaded = upload(alice, "deleted.png", "image/png", PNG_BYTES);
        UUID attachmentId = UUID.fromString(uploaded.get("id").asText());
        String filesUrl = uploaded.get("url").asText();
        MvcResult sent = postJson("/api/v1/conversations/" + conversationId + "/messages",
                alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "IMAGE",
                        "attachmentIds", List.of(attachmentId.toString())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode message = objectMapper.readTree(sent.getResponse().getContentAsString());
        UUID messageId = UUID.fromString(message.get("id").asText());
        long sequence = message.get("seq").asLong();
        assertThat(message.get("attachments")).hasSize(1);

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}/messages/{messageId}",
                        conversationId, messageId)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());

        List<MvcResult> deletedAttachmentDownloads = List.of(
                mockMvc.perform(get("/api/v1/attachments/{id}/content", attachmentId)
                                .header("Authorization", "Bearer " + alice.accessToken()))
                        .andReturn(),
                mockMvc.perform(get("/api/v1/attachments/{id}/content", attachmentId)
                                .header("Authorization", "Bearer " + bob.accessToken()))
                        .andReturn(),
                mockMvc.perform(get(filesUrl)
                                .header("Authorization", "Bearer " + alice.accessToken()))
                        .andReturn(),
                mockMvc.perform(get(filesUrl)
                                .header("Authorization", "Bearer " + bob.accessToken()))
                        .andReturn());
        assertThat(deletedAttachmentDownloads)
                .extracting(result -> result.getResponse().getStatus())
                .containsOnly(404);
        for (MvcResult download : deletedAttachmentDownloads) {
            assertThat(objectMapper.readTree(download.getResponse().getContentAsString())
                    .path("error").path("code").asText())
                    .isEqualTo("ATTACHMENT_NOT_FOUND");
        }

        MvcResult history = mockMvc.perform(get(
                        "/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tombstone = null;
        for (JsonNode item : objectMapper.readTree(history.getResponse().getContentAsString()).get("items")) {
            if (messageId.toString().equals(item.get("id").asText())) {
                tombstone = item;
                break;
            }
        }
        assertThat(tombstone).isNotNull();
        assertThat(tombstone.get("seq").asLong()).isEqualTo(sequence);
        assertThat(tombstone.has("content")).isTrue();
        assertThat(tombstone.get("content").isNull()).isTrue();
        assertThat(tombstone.get("deletedAt").isTextual()).isTrue();
        assertThat(tombstone.get("attachments")).isEmpty();
    }

    @Test
    void sendBindingRejectsBadClaimsAndTextAttachments() throws Exception {
        UUID conversationId = openDirectConversation();

        // alice uploads; bob cannot claim alice's upload.
        JsonNode aliceUpload = upload(alice, "a.png", "image/png", PNG_BYTES);
        postJson("/api/v1/conversations/" + conversationId + "/messages", bob.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "IMAGE",
                        "attachmentIds", List.of(aliceUpload.get("id").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // TEXT must not carry attachmentIds.
        postJson("/api/v1/conversations/" + conversationId + "/messages", alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT",
                        "content", "hi", "attachmentIds", List.of(aliceUpload.get("id").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // A pdf sniff cannot ride out as IMAGE (FILE type would accept it).
        JsonNode pdfUpload = upload(alice, "doc.pdf", "application/pdf",
                hex("255044462D312E340A25"));
        postJson("/api/v1/conversations/" + conversationId + "/messages", alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "IMAGE",
                        "attachmentIds", List.of(pdfUpload.get("id").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // …but binds fine as FILE.
        postJson("/api/v1/conversations/" + conversationId + "/messages", alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "FILE",
                        "attachmentIds", List.of(pdfUpload.get("id").asText())))
                .andExpect(status().isCreated());

        // Already-bound rows cannot be re-claimed.
        postJson("/api/v1/conversations/" + conversationId + "/messages", alice.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "FILE",
                        "attachmentIds", List.of(pdfUpload.get("id").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void exeDisguisedAsPng_isRejected415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "innocent.png", "image/png", EXE_BYTES);
        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("MIME_NOT_ALLOWED"));
    }

    @Test
    void oversizedUpload_isRejected413() throws Exception {
        byte[] tooBig = new byte[(int) AttachmentService.MAX_FILE_SIZE_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, tooBig, 0, PNG_BYTES.length); // valid sniff, too fat
        MockMultipartFile file = new MockMultipartFile("file", "huge.png", "image/png", tooBig);
        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void unauthenticatedRequests_areRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "p.png", "image/png", PNG_BYTES);
        mockMvc.perform(multipart("/api/v1/attachments").file(file))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/attachments/" + UUID.randomUUID() + "/content"))
                .andExpect(status().isUnauthorized());
    }
}

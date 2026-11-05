package com.nexachat.attachment;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        // +8491 phones belong to this suite — sibling ITs use other prefixes.
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE phone LIKE '+8491%')");
        jdbcTemplate.update(
                "DELETE FROM message_attachments WHERE owner_id IN (SELECT id FROM users WHERE phone LIKE '+8491%')");
        jdbcTemplate.update("DELETE FROM users WHERE phone LIKE '+8491%'");
        String tag = "ITA" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser("Zz" + tag + " Carol");
    }

    private static String randomPhone() {
        return "+8491" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
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

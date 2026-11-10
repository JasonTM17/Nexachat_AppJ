package com.nexachat.robustness;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexachat.robustness.RobustnessResponseAssert.isClientErrorWithCode;
import static com.nexachat.robustness.RobustnessResponseAssert.isSafeClientResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hostile multipart probes for the attachment and avatar upload endpoints.
 *
 * <p>Every rejected shape must be a clean 4xx with the shared error envelope:
 * no 5xx, no container/vendor stack detail, no partially stored row. The
 * duplicate-{@code file}-part and missing-part cases are ambiguous multipart
 * requests that Spring would otherwise resolve silently (first part) or let
 * escape the advice as a 500.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultipartInputRobustnessIT extends RobustnessITBase {

    /** Minimal PNG (signature + IHDR start) — passes the magic-byte whitelist. */
    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    /** PE/.exe header — not an image despite the declared type. */
    private static final byte[] EXE_BYTES = hex("4D5A90000300000004000000FFFF0000");

    /** HTML bytes: a stored-XSS probe if an image label were trusted. */
    private static final byte[] HTML_BYTES = "<html><body>owned</body></html>"
            .getBytes(StandardCharsets.UTF_8);

    private Registered alice;
    private Registered peer;
    private UUID groupId;

    @BeforeAll
    void seed() throws Exception {
        jdbcTemplate.update("TRUNCATE users CASCADE");
        alice = register("+84907500001", "Multipart Fuzz");
        peer = register("+84907500002", "Multipart Peer");
        MvcResult group = postJson("/api/v1/conversations/group",
                Map.of("title", "Avatar Matrix", "memberIds", List.of(peer.userId().toString())),
                alice.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        groupId = UUID.fromString(objectMapper.readTree(group.getResponse().getContentAsString())
                .get("id").asText());
    }

    // ----------------------------------------------------------- attachments

    @Test
    void attachmentUploadWithoutFilePartIsAClean400AndStoresNothing() throws Exception {
        long before = attachmentCount();

        mockMvc.perform(multipart("/api/v1/attachments")
                        .param("width", "16")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        assertThat(attachmentCount()).isEqualTo(before);
    }

    @Test
    void attachmentUploadWithTwoFilePartsIsRejectedAndStoresNothing() throws Exception {
        long before = attachmentCount();

        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(png("first.png"))
                        .file(png("second.png"))
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        assertThat(attachmentCount()).isEqualTo(before);
    }

    @Test
    void attachmentUploadWithMismatchedContentTypeIsRejected() throws Exception {
        long before = attachmentCount();

        // Declared image/png, but the bytes are active markup: the sniff is
        // unknown and the text fallback is closed to image labels.
        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "fake.png", "image/png", HTML_BYTES))
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isClientErrorWithCode("MIME_NOT_ALLOWED"));

        // Same story for a disguised executable.
        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "innocent.png", "image/png", EXE_BYTES))
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isClientErrorWithCode("MIME_NOT_ALLOWED"));

        assertThat(attachmentCount()).isEqualTo(before);
    }

    @Test
    void attachmentUploadWithEmptyFileIsRejected() throws Exception {
        long before = attachmentCount();

        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        assertThat(attachmentCount()).isEqualTo(before);
    }

    @Test
    void attachmentUploadOverTheCeilingIsRejectedBeforePersisting() throws Exception {
        long before = attachmentCount();
        byte[] tooBig = new byte[(int) com.nexachat.attachment.StorageService.MAX_FILE_SIZE_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, tooBig, 0, PNG_BYTES.length);

        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "huge.png", "image/png", tooBig))
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isClientErrorWithCode("FILE_TOO_LARGE"));

        assertThat(attachmentCount()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- avatars

    @Test
    void avatarUploadMatrixNeverReturns5xx() throws Exception {
        String token = alice.accessToken();
        String before = avatarUrl();

        // Missing part.
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        assertThat(avatarUrl()).isEqualTo(before);

        // Two file parts: ambiguous, rejected as validation.
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .file(png("one.png"))
                        .file(png("two.png"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        assertThat(avatarUrl()).isEqualTo(before);

        // Empty file.
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        assertThat(avatarUrl()).isEqualTo(before);

        // Declared image/png, but the bytes are HTML or an executable.
        for (byte[] bytes : List.of(HTML_BYTES, EXE_BYTES)) {
            mockMvc.perform(multipart("/api/v1/users/me/avatar")
                            .file(new MockMultipartFile("file", "fake.png", "image/png", bytes))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(isClientErrorWithCode("MIME_NOT_ALLOWED"));
        }
        assertThat(avatarUrl()).isEqualTo(before);

        // Oversize before any read/persist.
        byte[] tooBig = new byte[(int) com.nexachat.attachment.StorageService.MAX_FILE_SIZE_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, tooBig, 0, PNG_BYTES.length);
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .file(new MockMultipartFile("file", "huge.png", "image/png", tooBig))
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("FILE_TOO_LARGE"));
        assertThat(avatarUrl()).isEqualTo(before);

        // A genuine image still succeeds and the public URL is persisted.
        MvcResult success = mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .file(png("real.png"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(isSafeClientResponse())
                .andReturn();
        String avatarUrl = objectMapper.readTree(success.getResponse().getContentAsString())
                .get("avatarUrl").asText();
        assertThat(avatarUrl).startsWith("/api/v1/files/avatars/").endsWith(".png");
        assertThat(avatarUrl()).isEqualTo(avatarUrl);

        mockMvc.perform(get(avatarUrl).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(isSafeClientResponse());
    }

    // ---------------------------------------------------------- group avatars

    @Test
    void groupAvatarUploadMatrixNeverReturns5xx() throws Exception {
        String token = alice.accessToken();
        String path = "/api/v1/conversations/" + groupId + "/avatar";
        String before = groupAvatarUrl();

        // Missing part.
        mockMvc.perform(multipart(path).header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        assertThat(groupAvatarUrl()).isEqualTo(before);

        // Two file parts: ambiguous, rejected as validation.
        mockMvc.perform(multipart(path)
                        .file(png("one.png"))
                        .file(png("two.png"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        assertThat(groupAvatarUrl()).isEqualTo(before);

        // Empty file.
        mockMvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        assertThat(groupAvatarUrl()).isEqualTo(before);

        // Declared image/png, but the bytes are HTML or an executable.
        for (byte[] bytes : List.of(HTML_BYTES, EXE_BYTES)) {
            mockMvc.perform(multipart(path)
                            .file(new MockMultipartFile("file", "fake.png", "image/png", bytes))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(isClientErrorWithCode("MIME_NOT_ALLOWED"));
        }
        assertThat(groupAvatarUrl()).isEqualTo(before);

        // Oversize before any read/persist.
        byte[] tooBig = new byte[(int) com.nexachat.attachment.StorageService.MAX_FILE_SIZE_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, tooBig, 0, PNG_BYTES.length);
        mockMvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "huge.png", "image/png", tooBig))
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("FILE_TOO_LARGE"));
        assertThat(groupAvatarUrl()).isEqualTo(before);

        // Non-admin members cannot upload, even with valid bytes.
        mockMvc.perform(multipart(path)
                        .file(png("not-admin.png"))
                        .header("Authorization", "Bearer " + peer.accessToken()))
                .andExpect(isClientErrorWithCode("NOT_ADMIN"));
        assertThat(groupAvatarUrl()).isEqualTo(before);

        // A genuine image by the admin succeeds and the public URL is persisted.
        MvcResult success = mockMvc.perform(multipart(path)
                        .file(png("group.png"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(isSafeClientResponse())
                .andReturn();
        String avatarUrl = objectMapper.readTree(success.getResponse().getContentAsString())
                .get("avatarUrl").asText();
        assertThat(avatarUrl).startsWith("/api/v1/files/avatars/").endsWith(".png");
        assertThat(groupAvatarUrl()).isEqualTo(avatarUrl);

        mockMvc.perform(get(avatarUrl).header("Authorization", "Bearer " + peer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(isSafeClientResponse());
    }

    // ---------------------------------------------------------------- helpers

    private MockMultipartFile png(String filename) {
        return new MockMultipartFile("file", filename, "image/png", PNG_BYTES);
    }

    private long attachmentCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_attachments", Long.class);
        return count == null ? 0L : count;
    }

    private String avatarUrl() {
        return jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM users WHERE id = ?", String.class, alice.userId());
    }

    private String groupAvatarUrl() {
        return jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM conversations WHERE id = ?", String.class, groupId);
    }

    private static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

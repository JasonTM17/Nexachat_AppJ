package com.nexachat.attachment;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-level proof of the upload admission gate: with a one-slot gate the
 * second concurrent upload gets the standard {@code 429 RATE_LIMITED}
 * envelope plus {@code Retry-After}, and every exit path returns the slot.
 *
 * <p>{@link StorageService} is mocked so a test can hold the first upload
 * inside {@code store()} and make the overlap deterministic; no bytes touch
 * the filesystem.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nexachat.uploads.max-concurrent=1")
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadAdmissionFlowIT {

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(55_000);

    /** Minimal PNG (signature + IHDR start) — passes the magic-byte whitelist. */
    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        // The first upload holds a transaction while it blocks in store();
        // leave room for the competing request.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static byte[] hex(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private StorageService storageService;

    private UUID userId;
    private String accessToken;

    @BeforeAll
    void registerUploader() throws Exception {
        deleteUploaders();
        String phone = ItPhoneRanges.nextPhone(UploadAdmissionFlowIT.class);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", "Upload IT"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        userId = UUID.fromString(body.get("user").get("id").asText());
        accessToken = body.get("accessToken").asText();
    }

    @AfterAll
    void deleteUploader() {
        deleteUploaders();
    }

    private void deleteUploaders() {
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE phone LIKE '+84956%')");
        jdbcTemplate.update(
                "DELETE FROM message_attachments WHERE owner_id IN (SELECT id FROM users WHERE phone LIKE '+84956%')");
        jdbcTemplate.update("DELETE FROM users WHERE phone LIKE '+84956%'");
    }

    @BeforeEach
    void resetStorage() {
        Mockito.reset(storageService);
        lenient().when(storageService.resolveUrl(anyString()))
                .thenAnswer(i -> "/api/v1/files/" + i.getArgument(0));
    }

    private MockHttpServletRequestBuilder attachmentUpload() {
        return multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                .header("Authorization", "Bearer " + accessToken);
    }

    private MockHttpServletRequestBuilder avatarUpload() {
        return multipart("/api/v1/users/me/avatar")
                .file(new MockMultipartFile("file", "me.png", "image/png", PNG_BYTES))
                .header("Authorization", "Bearer " + accessToken);
    }

    private void stubSuccessfulStore() {
        when(storageService.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenAnswer(invocation -> new StorageService.StoredFile(
                        "attachments/" + UUID.randomUUID() + ".png",
                        PNG_BYTES.length, "image/png"));
    }

    @Test
    void secondConcurrentUpload_isRejectedWithTheRateLimitEnvelope() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(storageService.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release latch timed out");
                    }
                    return new StorageService.StoredFile(
                            "attachments/" + UUID.randomUUID() + ".png",
                            PNG_BYTES.length, "image/png");
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(
                    () -> mockMvc.perform(attachmentUpload()).andReturn());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(attachmentUpload())
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.error.code").value(ApiErrorCode.RATE_LIMITED.getCode()))
                    .andExpect(jsonPath("$.error.details.retryAfterSeconds").value(1));

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void successfulUpload_returnsTheSlotForTheNextCall() throws Exception {
        stubSuccessfulStore();

        mockMvc.perform(attachmentUpload()).andExpect(status().isCreated());
        mockMvc.perform(attachmentUpload()).andExpect(status().isCreated());
    }

    @Test
    void validationFailure_returnsTheSlot() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "note.txt", "text/plain",
                                "not an image".getBytes()))
                        .param("blurHash", "LEHV6nWB2yk8pyo0adR*.7kCMdnj")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        stubSuccessfulStore();
        mockMvc.perform(attachmentUpload()).andExpect(status().isCreated());
    }

    @Test
    void storageFailure_returnsTheSlot() throws Exception {
        when(storageService.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenThrow(ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to store file"));

        mockMvc.perform(attachmentUpload())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        stubSuccessfulStore();
        mockMvc.perform(attachmentUpload()).andExpect(status().isCreated());
    }

    @Test
    void avatarUploadAndAttachmentUploadShareTheSameGate() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(storageService.store(eq("avatars"), any(), any(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release latch timed out");
                    }
                    return new StorageService.StoredFile(
                            "avatars/" + UUID.randomUUID() + ".png",
                            PNG_BYTES.length, "image/png");
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(
                    () -> mockMvc.perform(avatarUpload()).andReturn());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(attachmentUpload())
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value(ApiErrorCode.RATE_LIMITED.getCode()));

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}

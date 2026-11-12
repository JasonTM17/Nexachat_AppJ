package com.nexachat.attachment;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves where multipart parts live while an upload is in flight and that the
 * servlet container removes them on every exit path.
 *
 * <p>The configured scratch directory is supplied through the same environment
 * variable a deployment uses ({@code NEXACHAT_MULTIPART_TEMP_DIR}). The storage
 * bean is mocked so a test can observe the part on disk while the controller
 * is still inside {@code store()} without writing a real object.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"it", "render"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultipartTempFileIT {

    private static final Path SCRATCH_PARENT = createTempDir("nexachat-multipart-it");
    /** Deliberately missing at boot: MultipartTempDirectory must create it. */
    private static final Path MULTIPART_DIR = SCRATCH_PARENT.resolve("scratch/nexachat");
    private static final Path STORAGE_ROOT = createTempDir("nexachat-multipart-storage");

    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("nexachat.storage.root", STORAGE_ROOT::toString);
        registry.add("NEXACHAT_MULTIPART_TEMP_DIR", MULTIPART_DIR::toString);
    }

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MultipartConfigElement multipartConfig;

    @MockitoBean
    private StorageService storageService;

    private String accessToken;

    @BeforeAll
    void registerUploader() throws Exception {
        String phone = ItPhoneRanges.nextPhone(MultipartTempFileIT.class);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/register",
                jsonEntity("""
                        {"phone":"%s","password":"secret123","displayName":"Multipart IT"}
                        """.formatted(phone)),
                String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        accessToken = objectMapper.readTree(response.getBody())
                .path("accessToken").asText();
    }

    @BeforeEach
    void stubStorageUrl() {
        lenient().when(storageService.resolveUrl(anyString()))
                .thenAnswer(invocation -> "/api/v1/files/" + invocation.getArgument(0));
    }

    @AfterAll
    void cleanUp() throws IOException {
        deleteRecursively(SCRATCH_PARENT);
        deleteRecursively(STORAGE_ROOT);
    }

    @Test
    void theConfiguredDirectoryIsCreatedBeforeTheFirstRequest() {
        assertThat(multipartConfig.getLocation()).isEqualTo(MULTIPART_DIR.toString());
        assertThat(MULTIPART_DIR).isDirectory();
    }

    // ---------------------------------------------------------------- success

    @Test
    void partsLandInTheConfiguredDirectoryAndAreDeletedAfterSuccess() throws Exception {
        AtomicReference<Path> partOnDisk = new AtomicReference<>();
        AtomicReference<byte[]> partBytes = new AtomicReference<>();
        when(storageService.store(anyString(), anyString(), any(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    List<Path> parts = regularFiles(MULTIPART_DIR);
                    if (parts.size() == 1) {
                        partOnDisk.set(parts.get(0));
                        partBytes.set(Files.readAllBytes(parts.get(0)));
                    }
                    return new StorageService.StoredFile(
                            "attachments/" + UUID.randomUUID() + ".png",
                            PNG_BYTES.length, "image/png");
                });

        ResponseEntity<String> response = upload("", PNG_BYTES, "photo.png");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(partOnDisk.get())
                .as("exactly one part must be written under NEXACHAT_MULTIPART_TEMP_DIR")
                .isNotNull()
                .satisfies(part -> assertThat(part.startsWith(MULTIPART_DIR)).isTrue());
        assertThat(partBytes.get()).containsExactly(PNG_BYTES);
        awaitEmpty();
    }

    // --------------------------------------------------------------- failures

    @Test
    void partsAreDeletedAfterAValidationError() throws Exception {
        ResponseEntity<String> response = upload("?width=-1", PNG_BYTES, "photo.png");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo(ApiErrorCode.VALIDATION_FAILED.getCode());
        verify(storageService, never()).store(anyString(), anyString(), any(), any(byte[].class));
        awaitEmpty();
    }

    @Test
    void partsAreDeletedAfterAStorageFailure() throws Exception {
        when(storageService.store(anyString(), anyString(), any(), any(byte[].class)))
                .thenThrow(ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to store file"));

        ResponseEntity<String> response = upload("", PNG_BYTES, "photo.png");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(errorCode(response)).isEqualTo(ApiErrorCode.INTERNAL_ERROR.getCode());
        assertThat(response.getBody())
                .doesNotContain("Exception", "stackTrace", "at com.nexachat");
        awaitEmpty();
    }

    @Test
    void partsAreDeletedAfterTheClientAborts() throws Exception {
        String boundary = "----nexachat-" + UUID.randomUUID();
        byte[] partHeader = ("""
                --%s\r
                Content-Disposition: form-data; name="file"; filename="abort.png"\r
                Content-Type: image/png\r
                \r
                """.formatted(boundary)).getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[256 * 1024];
        System.arraycopy(PNG_BYTES, 0, body, 0, PNG_BYTES.length);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            OutputStream out = socket.getOutputStream();
            String head = """
                    POST /api/v1/attachments HTTP/1.1\r
                    Host: 127.0.0.1:%d\r
                    Authorization: Bearer %s\r
                    Content-Type: multipart/form-data; boundary=%s\r
                    Content-Length: %d\r
                    Connection: close\r
                    \r
                    """.formatted(port, accessToken, boundary,
                    partHeader.length + body.length + 1024 * 1024);
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(partHeader);
            out.write(body);
            out.flush();

            await(Duration.ofSeconds(10), () -> !regularFiles(MULTIPART_DIR).isEmpty(),
                    "part must be on disk while the client is still sending");
            socket.close();
        }

        awaitEmpty();
        verify(storageService, never()).store(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void anUnusableTempDirectoryFailsWithTheErrorEnvelope() throws Exception {
        when(storageService.store(anyString(), anyString(), any(), any(byte[].class)))
                .thenReturn(new StorageService.StoredFile(
                        "attachments/" + UUID.randomUUID() + ".png",
                        PNG_BYTES.length, "image/png"));
        Path aside = MULTIPART_DIR.resolveSibling(MULTIPART_DIR.getFileName() + "-aside");
        Files.move(MULTIPART_DIR, aside);
        Files.writeString(MULTIPART_DIR, "not a directory");
        try {
            ResponseEntity<String> response = upload("", PNG_BYTES, "photo.png");

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo(ApiErrorCode.VALIDATION_FAILED.getCode());
            assertThat(response.getBody())
                    .doesNotContain("Exception", "stackTrace", "at com.nexachat",
                            "not a directory");
        } finally {
            Files.deleteIfExists(MULTIPART_DIR);
            deleteRecursively(aside);
            Files.createDirectories(MULTIPART_DIR);
        }
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<String> upload(String query, byte[] bytes, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(accessToken);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return restTemplate.postForEntity("/api/v1/attachments" + query,
                new HttpEntity<>(body, headers), String.class);
    }

    private HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private String errorCode(ResponseEntity<String> response) throws IOException {
        JsonNode body = objectMapper.readTree(response.getBody());
        return body.path("error").path("code").asText();
    }

    private static List<Path> regularFiles(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).toList();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void awaitEmpty() {
        await(Duration.ofSeconds(10), () -> regularFiles(MULTIPART_DIR).isEmpty(),
                "the servlet container must delete every part when the request ends");
    }

    private static void await(Duration timeout, BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        assertThat(condition.getAsBoolean()).as(message).isTrue();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
    }
}

package com.nexachat.attachment;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Proves the unchanged default with the production Render profile: without
 * {@code NEXACHAT_MULTIPART_TEMP_DIR}, parts are written to the servlet
 * container's own temp directory (under {@code server.tomcat.basedir}) and are
 * deleted when the request ends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"it", "render"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultipartDefaultTempFileIT {

    private static final Path TOMCAT_BASE = createTempDir("nexachat-tomcat-base");
    private static final Path STORAGE_ROOT = createTempDir("nexachat-default-storage");

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
        registry.add("server.tomcat.basedir", TOMCAT_BASE::toString);
        // Pin the deployment switch to empty so this test exercises the default.
        registry.add("NEXACHAT_MULTIPART_TEMP_DIR", () -> "");
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
        String phone = ItPhoneRanges.nextPhone(MultipartDefaultTempFileIT.class);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/register",
                jsonEntity("""
                        {"phone":"%s","password":"secret123","displayName":"Default multipart IT"}
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
        ItPhoneRanges.clean(jdbcTemplate, MultipartDefaultTempFileIT.class);
        deleteRecursively(TOMCAT_BASE);
        deleteRecursively(STORAGE_ROOT);
    }

    @Test
    void defaultLocationIsTheContainerTempDirectoryAndPartsAreDeleted() throws Exception {
        assertThat(multipartConfig.getLocation()).isBlank();

        AtomicReference<Path> partOnDisk = new AtomicReference<>();
        AtomicReference<byte[]> partBytes = new AtomicReference<>();
        when(storageService.store(anyString(), anyString(), any(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    List<Path> parts = regularFilesUnder(TOMCAT_BASE);
                    if (parts.size() == 1) {
                        partOnDisk.set(parts.get(0));
                        partBytes.set(Files.readAllBytes(parts.get(0)));
                    }
                    return new StorageService.StoredFile(
                            "attachments/" + java.util.UUID.randomUUID() + ".png",
                            PNG_BYTES.length, "image/png");
                });

        ResponseEntity<String> response = upload();

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(partOnDisk.get())
                .as("the part must be written under the Tomcat base directory")
                .isNotNull()
                .satisfies(part -> assertThat(part.startsWith(TOMCAT_BASE)).isTrue());
        assertThat(TOMCAT_BASE.relativize(partOnDisk.get()).toString())
                .startsWith("work");
        assertThat(partBytes.get()).containsExactly(PNG_BYTES);
        await(Duration.ofSeconds(10), () -> regularFilesUnder(TOMCAT_BASE).isEmpty(),
                "the container must delete the part when the request ends");
    }

    private ResponseEntity<String> upload() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(accessToken);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(PNG_BYTES) {
            @Override
            public String getFilename() {
                return "photo.png";
            }
        });
        return restTemplate.postForEntity("/api/v1/attachments",
                new HttpEntity<>(body, headers), String.class);
    }

    private HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private static List<Path> regularFilesUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
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

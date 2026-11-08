package com.nexachat.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database health content: db, flyway and the upload-dir disk probe must
 * all be present and UP, and the Prometheus endpoint must expose the NexaChat
 * metric names.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class ObservabilityHealthIT {

    private static final int MANAGEMENT_PORT = freePort();
    private static final Path STORAGE_ROOT = tempStorageRoot();

    @DynamicPropertySource
    static void observabilityProperties(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> String.valueOf(MANAGEMENT_PORT));
        registry.add("management.server.address", () -> "127.0.0.1");
        registry.add("management.endpoints.web.exposure.include",
                () -> "health,metrics,prometheus");
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("nexachat.storage.root", () -> STORAGE_ROOT.toString());
    }

    @LocalManagementPort
    private int managementPort;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthIncludesDatabaseFlywayAndDiskSpace() {
        ResponseEntity<String> response =
                rest.getForEntity(url(managementPort, "/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).contains("db");
        assertThat(response.getBody()).contains("flyway");
        assertThat(response.getBody()).contains("diskSpace");
    }

    @Test
    void readinessGroupIncludesDatabaseFlywayAndDiskSpace() {
        ResponseEntity<String> response =
                rest.getForEntity(url(managementPort, "/actuator/health/readiness"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("db");
        assertThat(response.getBody()).contains("flyway");
        assertThat(response.getBody()).contains("diskSpace");
    }

    @Test
    void prometheusRequiresAuthentication() {
        ResponseEntity<String> response =
                rest.getForEntity(url(managementPort, "/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static String url(int port, String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not allocate a management port", exception);
        }
    }

    private static Path tempStorageRoot() {
        try {
            return Files.createTempDirectory("nexachat-obs-health-it-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create a storage root", exception);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

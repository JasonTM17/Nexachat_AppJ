package com.nexachat.observability;

import com.nexachat.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Health content and management-port isolation. The management server runs on
 * its own loopback port; the main API port must not serve actuator endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ObservabilityHealthTest extends ObservabilityHealthTestBase {

    private static final int MANAGEMENT_PORT = freePort();
    private static final Path STORAGE_ROOT = tempStorageRoot();

    @DynamicPropertySource
    static void observabilityProperties(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> String.valueOf(MANAGEMENT_PORT));
        registry.add("management.server.address", () -> "127.0.0.1");
        registry.add("management.endpoints.web.exposure.include",
                () -> "health,metrics,prometheus");
        registry.add("nexachat.storage.root", () -> STORAGE_ROOT.toString());
    }

    @LocalServerPort
    private int serverPort;
    @LocalManagementPort
    private int managementPort;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void actuatorIsNotServedOnTheMainPort() {
        ResponseEntity<String> response =
                rest.getForEntity(url(serverPort, "/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void healthIncludesDiskFlywayAndPing() {
        ResponseEntity<String> response =
                rest.getForEntity(url(managementPort, "/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).contains("diskSpace");
        assertThat(response.getBody()).contains("flyway");
        assertThat(response.getBody()).contains("ping");
    }

    @Test
    void livenessAndReadinessGroupsAreExposed() {
        ResponseEntity<String> liveness =
                rest.getForEntity(url(managementPort, "/actuator/health/liveness"), String.class);
        ResponseEntity<String> readiness =
                rest.getForEntity(url(managementPort, "/actuator/health/readiness"), String.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).contains("ping");
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).contains("diskSpace");
        assertThat(readiness.getBody()).contains("flyway");
        assertThat(readiness.getBody()).contains("refreshReplay");
    }

    @Test
    void metricsRequireAuthenticationWithoutAToken() {
        ResponseEntity<String> response =
                rest.getForEntity(url(managementPort, "/actuator/metrics"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void metricsAndPrometheusAreExposedWithATokenWhenTheFlagIsOn() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken(jwtService, UUID.randomUUID()));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> metrics = rest.exchange(
                url(managementPort, "/actuator/metrics"), HttpMethod.GET, entity, String.class);
        ResponseEntity<String> prometheus = rest.exchange(
                url(managementPort, "/actuator/prometheus"), HttpMethod.GET, entity, String.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody()).contains("nexachat.messages.sent");
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody()).contains("nexachat_messages_sent_total");
    }
}

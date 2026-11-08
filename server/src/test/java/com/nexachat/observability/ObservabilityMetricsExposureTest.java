package com.nexachat.observability;

import com.nexachat.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
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
 * With the exposure flag left at its default, metrics and prometheus are not
 * exposed even on the management port; health still is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ObservabilityMetricsExposureTest extends ObservabilityHealthTestBase {

    private static final int MANAGEMENT_PORT = freePort();
    private static final Path STORAGE_ROOT = tempStorageRoot();

    @DynamicPropertySource
    static void observabilityProperties(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> String.valueOf(MANAGEMENT_PORT));
        registry.add("management.server.address", () -> "127.0.0.1");
        registry.add("management.endpoints.web.exposure.include", () -> "health");
        registry.add("nexachat.storage.root", () -> STORAGE_ROOT.toString());
    }

    @LocalManagementPort
    private int managementPort;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwtService;

    @Test
    void metricsAndPrometheusAreHiddenWithATokenWhenTheFlagIsOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken(jwtService, UUID.randomUUID()));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> metrics = rest.exchange(
                url(managementPort, "/actuator/metrics"), HttpMethod.GET, entity, String.class);
        ResponseEntity<String> prometheus = rest.exchange(
                url(managementPort, "/actuator/prometheus"), HttpMethod.GET, entity, String.class);
        ResponseEntity<String> health =
                rest.getForEntity(url(managementPort, "/actuator/health"), String.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void metricsStillRequireAuthenticationWhenTheFlagIsOff() {
        ResponseEntity<String> metrics =
                rest.getForEntity(url(managementPort, "/actuator/metrics"), String.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

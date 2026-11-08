package com.nexachat.observability;

import com.nexachat.message.MessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the repository timing aspect is applied to the real Spring Data
 * repository proxy, not just to unit-test mocks.
 */
@SpringBootTest
@ActiveProfiles("it")
class ObservabilityRepositoryMetricsIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // MOCK web environment: no management context is needed.
        registry.add("management.server.port", () -> "-1");
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }

    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void repositoryCallsAreTimedWithTheMethodName() {
        messageRepository.count();

        var timer = meterRegistry.find(ObservabilityMetrics.REPOSITORY_CALLS)
                .tag("method", "CrudRepository.count")
                .tag("outcome", "SUCCESS")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

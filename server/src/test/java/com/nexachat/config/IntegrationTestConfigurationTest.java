package com.nexachat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards parity between integration-test and production JPA session boundaries.
 */
class IntegrationTestConfigurationTest {

    @Test
    void integrationProfileDisablesOpenInView() throws IOException {
        PropertySource<?> integration = new YamlPropertySourceLoader()
                .load("integration", new ClassPathResource("application-it.yml"))
                .get(0);

        assertThat(integration.getProperty("spring.jpa.open-in-view")).isEqualTo(false);
    }
}

package com.nexachat.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class HermeticTestConfigurationTest {

    @Test
    void testProfilesSetAnExplicitSecurityPasswordToAvoidBootLoggingOne() throws IOException {
        Properties forkProperties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/hermetic-test.properties")) {
            assertThat(input).isNotNull();
            forkProperties.load(input);
        }

        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-it.yml"));
        Properties integrationProperties = yaml.getObject();
        String forkPassword = forkProperties.getProperty("spring.security.user.password");
        String integrationPassword = integrationProperties == null
                ? null : integrationProperties.getProperty("spring.security.user.password");

        assertThat(forkPassword != null && !forkPassword.isBlank()
                        || integrationPassword != null && !integrationPassword.isBlank())
                .as("Spring Boot must not generate and log an integration-test credential")
                .isTrue();
    }
}

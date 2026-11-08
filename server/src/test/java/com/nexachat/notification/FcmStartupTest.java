package com.nexachat.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class FcmStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayConfiguration.class);

    @Test
    void enabledWithoutServiceAccountFailsFast() {
        runner.withPropertyValues(
                        "nexachat.fcm.enabled=true",
                        "nexachat.fcm.project-id=test-project",
                        "nexachat.fcm.service-account-json=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("service-account-json");
                });
    }

    @Test
    void enabledWithMalformedServiceAccountFailsFast() {
        runner.withPropertyValues(
                        "nexachat.fcm.enabled=true",
                        "nexachat.fcm.project-id=test-project",
                        "nexachat.fcm.service-account-json=not-json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("malformed");
                });
    }

    @Test
    void disabledWithoutCredentialsStarts() {
        runner.withPropertyValues(
                        "nexachat.fcm.enabled=false",
                        "nexachat.fcm.service-account-json=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(GoogleHttpFcmGateway.class)).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FcmProperties.class)
    static class GatewayConfiguration {

        @Bean
        @ConditionalOnProperty(name = "nexachat.fcm.enabled", havingValue = "true")
        GoogleHttpFcmGateway googleHttpFcmGateway(FcmProperties properties, ObjectMapper objectMapper) {
            return new GoogleHttpFcmGateway(properties, objectMapper);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}

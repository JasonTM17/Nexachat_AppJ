package com.nexachat.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the S3 deadlines bind from the deployment environment through
 * {@code application.yml}, keep their documented defaults, and fail startup
 * on a non-positive value or an attempt deadline beyond the call deadline.
 */
class S3StoragePropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(S3StorageProperties.class)
    static class BindingConfiguration {
    }

    @Test
    void environmentValuesBindThroughTheApplicationConfiguration() {
        Map<String, Object> environment = requiredEnvironment();
        environment.put("NEXACHAT_STORAGE_S3_API_CALL_TIMEOUT", "45s");
        environment.put("NEXACHAT_STORAGE_S3_API_CALL_ATTEMPT_TIMEOUT", "20s");
        environment.put("NEXACHAT_STORAGE_S3_CONNECTION_TIMEOUT", "3s");
        environment.put("NEXACHAT_STORAGE_S3_SOCKET_TIMEOUT", "12s");

        context(environment).run(context -> {
            assertThat(context).hasNotFailed();
            S3StorageProperties properties = context.getBean(S3StorageProperties.class);
            assertThat(properties.apiCallTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.apiCallAttemptTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.connectionTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.socketTimeout()).isEqualTo(Duration.ofSeconds(12));
        });
    }

    @Test
    void documentedDefaultsApplyWhenTheEnvironmentLeavesThemUnset() {
        context(requiredEnvironment()).run(context -> {
            assertThat(context).hasNotFailed();
            S3StorageProperties properties = context.getBean(S3StorageProperties.class);
            assertThat(properties.apiCallTimeout())
                    .isEqualTo(S3StorageProperties.DEFAULT_API_CALL_TIMEOUT);
            assertThat(properties.apiCallAttemptTimeout())
                    .isEqualTo(S3StorageProperties.DEFAULT_API_CALL_ATTEMPT_TIMEOUT);
            assertThat(properties.connectionTimeout())
                    .isEqualTo(S3StorageProperties.DEFAULT_CONNECTION_TIMEOUT);
            assertThat(properties.socketTimeout())
                    .isEqualTo(S3StorageProperties.DEFAULT_SOCKET_TIMEOUT);
        });
    }

    @Test
    void anAttemptDeadlineBeyondTheCallDeadlineFailsStartup() {
        Map<String, Object> environment = requiredEnvironment();
        environment.put("NEXACHAT_STORAGE_S3_API_CALL_TIMEOUT", "5s");
        environment.put("NEXACHAT_STORAGE_S3_API_CALL_ATTEMPT_TIMEOUT", "10s");

        context(environment).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("must not exceed");
        });
    }

    @Test
    void aNonPositiveDeadlineFailsStartup() {
        Map<String, Object> environment = requiredEnvironment();
        environment.put("NEXACHAT_STORAGE_S3_SOCKET_TIMEOUT", "0s");

        context(environment).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("must be positive");
        });
    }

    private static Map<String, Object> requiredEnvironment() {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("NEXACHAT_STORAGE_S3_ENDPOINT", "http://localhost:9000");
        environment.put("NEXACHAT_STORAGE_S3_BUCKET", "nexachat-test");
        environment.put("NEXACHAT_STORAGE_S3_ACCESS_KEY_ID", "test-access-key");
        environment.put("NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY", "test-secret-key");
        return environment;
    }

    private static ApplicationContextRunner context(Map<String, Object> environment) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer((ConfigurableApplicationContext context) -> {
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("testEnvironment", environment));
                })
                .withUserConfiguration(BindingConfiguration.class);
    }
}

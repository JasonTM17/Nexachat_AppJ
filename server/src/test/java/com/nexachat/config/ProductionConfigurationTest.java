package com.nexachat.config;

import com.nexachat.auth.JwtProperties;
import com.nexachat.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigurationTest {

    private static final String TEST_JWT_SECRET = "test-only-jwt-secret-with-at-least-32-bytes";

    @Test
    void productionDatasourceCredentialsRequireEnvironmentValues() throws IOException {
        PropertySource<?> production = new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-prod.yml"))
                .get(0);

        assertThat(production.getProperty("spring.datasource.username"))
                .isEqualTo("${DATABASE_USER}");
        assertThat(production.getProperty("spring.datasource.password"))
                .isEqualTo("${DATABASE_PASSWORD}");
        assertThat(production.getProperty("spring.datasource.url")).isEqualTo("${DATABASE_URL}");
        assertThat(production.getProperty("nexachat.jwt.secret")).isEqualTo("${JWT_SECRET}");
        assertThat(production.getProperty("nexachat.storage.root")).isEqualTo("${STORAGE_ROOT}");
    }

    @Test
    void productionDatasourceBindsComposeDatabaseEnvironment() throws IOException {
        productionContext(Map.of(
                "DATABASE_URL", "jdbc:postgresql://db:5432/nexachat",
                "DATABASE_USER", "compose-user",
                "DATABASE_PASSWORD", "compose-password",
                "JWT_SECRET", TEST_JWT_SECRET,
                "STORAGE_ROOT", "/tmp/nexachat-test-uploads")).run(context -> {
            assertThat(context).hasNotFailed();
            DataSourceProperties dataSource = context.getBean(DataSourceProperties.class);
            assertThat(context.getEnvironment().getActiveProfiles()).contains("prod");
            assertThat(dataSource.getUrl()).isEqualTo("jdbc:postgresql://db:5432/nexachat");
            assertThat(dataSource.getUsername()).isEqualTo("compose-user");
            assertThat(dataSource.getPassword()).isEqualTo("compose-password");
            assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
                    .isEqualTo("health");
        });
    }

    @Test
    void productionConfigurationUsesGracefulShutdownBoundedPoolAndUploadLimits() throws IOException {
        PropertySource<?> production = productionProperties();

        assertThat(production.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(production.getProperty("server.compression.enabled")).isEqualTo(true);
        assertThat(production.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
        assertThat(production.getProperty("spring.jpa.open-in-view")).isEqualTo(false);
        assertThat(production.getProperty("spring.flyway.validate-on-migrate")).isEqualTo(true);
        assertThat(production.getProperty("spring.jackson.deserialization.fail-on-unknown-properties"))
                .isEqualTo(false);
        assertThat(production.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo(10);
        assertThat(production.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo(2);
        assertThat(production.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo(30000);
        assertThat(production.getProperty("spring.datasource.hikari.validation-timeout")).isEqualTo(5000);
        assertThat(production.getProperty("spring.datasource.hikari.max-lifetime")).isEqualTo(1800000);
        assertThat(production.getProperty("spring.datasource.hikari.leak-detection-threshold")).isEqualTo(60000);
        assertThat(production.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("25MB");
        assertThat(production.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("25MB");
    }

    @Test
    void productionConfigurationKeepsErrorsRedactedAndLoggingAtInfo() throws IOException {
        PropertySource<?> production = productionProperties();

        assertThat(production.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(production.getProperty("server.error.include-exception")).isEqualTo(false);
        assertThat(production.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(production.getProperty("server.error.include-binding-errors")).isEqualTo("never");
        assertThat(production.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(production.getProperty("logging.level.root")).isEqualTo("INFO");
        assertThat(production.getProperty("logging.level.com.nexachat")).isEqualTo("INFO");
    }

    @Test
    void readinessIncludesRefreshReplayConfigurationState() throws IOException {
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        assertThat(application.getProperty("management.endpoint.health.group.readiness.include"))
                .asString().contains("refreshReplay");
    }

    @Test
    void productionDatasourceFailsWhenRequiredCredentialsAreMissing() throws IOException {
        productionContext(Map.of(
                "DATABASE_URL", "jdbc:postgresql://db:5432/nexachat",
                "DATABASE_USER", "test-user",
                "JWT_SECRET", TEST_JWT_SECRET,
                "STORAGE_ROOT", "/tmp/nexachat-test-uploads")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("DATABASE_PASSWORD");
        });
    }

    @Test
    void productionJwtSecretRejectsTheDevelopmentDefault() {
        productionContext(Map.of(
                "DATABASE_URL", "jdbc:postgresql://db:5432/nexachat",
                "DATABASE_USER", "test-user",
                "DATABASE_PASSWORD", "test-password",
                "JWT_SECRET", "dev-secret-change-me-at-least-64-chars-long-0123456789",
                "STORAGE_ROOT", "/tmp/nexachat-test-uploads")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("known development or low-diversity");
        });
    }

    @Test
    void productionJwtSecretFailsFastWhenEnvironmentValueIsMissing() {
        productionContext(Map.of(
                "DATABASE_URL", "jdbc:postgresql://db:5432/nexachat",
                "DATABASE_USER", "test-user",
                "DATABASE_PASSWORD", "test-password",
                "STORAGE_ROOT", "/tmp/nexachat-test-uploads")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure().toString())
                    .contains("must be at least 32 bytes (256-bit) for HS256");
        });
    }

    @Test
    void productionStorageRootFailsFastWhenEnvironmentValueIsMissing() throws IOException {
        productionContext(Map.of(
                "DATABASE_URL", "jdbc:postgresql://db:5432/nexachat",
                "DATABASE_USER", "test-user",
                "DATABASE_PASSWORD", "test-password",
                "JWT_SECRET", TEST_JWT_SECRET)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("STORAGE_ROOT");
        });
    }

    private static ApplicationContextRunner productionContext(Map<String, Object> variables) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer((ConfigurableApplicationContext context) -> {
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("testEnvironment", variables));
                })
                .withPropertyValues("spring.profiles.active=prod")
                .withUserConfiguration(DataSourceBindingConfiguration.class,
                        ProductionDataSourceConfiguration.class,
                        ProductionJwtConfiguration.class,
                        ProductionJwtSecretGuard.class);
    }

    private static PropertySource<?> productionProperties() throws IOException {
        return new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-prod.yml"))
                .get(0);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class DataSourceBindingConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class ProductionJwtConfiguration {

        @Bean
        JwtService jwtService(JwtProperties properties) {
            return new JwtService(properties);
        }
    }
}

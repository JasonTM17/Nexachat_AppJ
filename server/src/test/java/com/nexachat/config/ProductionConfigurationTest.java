package com.nexachat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigurationTest {

    @Test
    void productionDatasourceCredentialsRequireEnvironmentValues() throws IOException {
        PropertySource<?> production = new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-prod.yml"))
                .get(0);

        assertThat(production.getProperty("spring.datasource.username"))
                .isEqualTo("${DATABASE_USER}");
        assertThat(production.getProperty("spring.datasource.password"))
                .isEqualTo("${DATABASE_PASSWORD}");
    }

    @Test
    void productionDatasourceBindsComposeSpringDatasourceEnvironment() throws IOException {
        productionContext(Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://db:5432/nexachat",
                "SPRING_DATASOURCE_USERNAME", "compose-user",
                "SPRING_DATASOURCE_PASSWORD", "compose-password")).run(context -> {
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
    void productionDatasourceFailsWhenComposeCredentialsAreMissing() throws IOException {
        productionContext(Map.of()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("DATABASE_USER");
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
                        ProductionDataSourceConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class DataSourceBindingConfiguration {
    }
}

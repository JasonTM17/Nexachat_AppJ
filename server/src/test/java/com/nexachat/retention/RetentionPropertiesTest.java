package com.nexachat.retention;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void retentionIsOptInAndDryRunIsTheDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RetentionProperties properties = context.getBean(RetentionProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.isDryRun()).isTrue();
            assertThat(properties.getBatchSize()).isBetween(1, 500);
            for (RetentionJobRunner.Job job : RetentionJobRunner.Job.values()) {
                assertThat(properties.isJobEnabled(job)).as(job.id()).isFalse();
            }
        });
    }

    @Test
    void invalidBatchSizeFailsApplicationStartupValidation() {
        runner.withPropertyValues("nexachat.retention.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidRetentionAgeFailsApplicationStartupValidation() {
        runner.withPropertyValues("nexachat.retention.jobs.call-records.retention-days=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidBatchSizeFromComposeStyleEnvironmentVariableFailsValidation() {
        var source = new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("NEXACHAT_RETENTION_BATCH_SIZE", "0"));
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        RetentionProperties properties = binder
                .bind("nexachat.retention", Bindable.of(RetentionProperties.class))
                .get();
        Set<ConstraintViolation<RetentionProperties>> violations = Validation
                .buildDefaultValidatorFactory()
                .getValidator()
                .validate(properties);
        assertThat(violations).isNotEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetentionProperties.class)
    static class PropertiesConfiguration {
    }
}

package com.nexachat.attachment;

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

class AvatarSweepPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void sweepIsOptInAndDryRunIsTheDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AvatarSweepProperties properties = context.getBean(AvatarSweepProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.isDryRun()).isTrue();
            assertThat(properties.getBatchSize()).isBetween(1, 500);
            assertThat(properties.getMinAgeHours()).isEqualTo(24);
        });
    }

    @Test
    void invalidBatchSizeFailsApplicationStartupValidation() {
        runner.withPropertyValues("nexachat.avatar-sweep.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidAgeFailsApplicationStartupValidation() {
        runner.withPropertyValues("nexachat.avatar-sweep.min-age-hours=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidBatchSizeFromComposeStyleEnvironmentVariableFailsValidation() {
        var source = new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("NEXACHAT_AVATAR_SWEEP_BATCH_SIZE", "0"));
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        AvatarSweepProperties properties = binder
                .bind("nexachat.avatar-sweep", Bindable.of(AvatarSweepProperties.class))
                .get();
        Set<ConstraintViolation<AvatarSweepProperties>> violations = Validation
                .buildDefaultValidatorFactory()
                .getValidator()
                .validate(properties);
        assertThat(violations).isNotEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AvatarSweepProperties.class)
    static class PropertiesConfiguration {
    }
}

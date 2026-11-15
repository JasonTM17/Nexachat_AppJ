package com.nexachat.e2ee;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class E2eePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void v1ActivationDefaultsToDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            E2eeProperties properties = context.getBean(E2eeProperties.class);
            assertThat(properties.isV1ActivationEnabled()).isFalse();
        });
    }

    @Test
    void explicitTrueBinds() {
        runner.withPropertyValues("nexachat.e2ee.v1-activation-enabled=true")
                .run(context -> assertThat(context.getBean(E2eeProperties.class)
                        .isV1ActivationEnabled()).isTrue());
    }

    @Test
    void explicitFalseBinds() {
        runner.withPropertyValues("nexachat.e2ee.v1-activation-enabled=false")
                .run(context -> assertThat(context.getBean(E2eeProperties.class)
                        .isV1ActivationEnabled()).isFalse());
    }

    @Test
    void malformedValueFailsBinding() {
        runner.withPropertyValues("nexachat.e2ee.v1-activation-enabled=banana")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void malformedEnvironmentVariableNeverEnablesActivation() {
        var source = new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("NEXACHAT_E2EE_V1_ACTIVATION_ENABLED", "banana"));
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        assertThatThrownBy(() -> binder
                .bind("nexachat.e2ee", Bindable.of(E2eeProperties.class)).get())
                .isInstanceOf(BindException.class);
    }

    @Test
    void composeStyleEnvironmentVariableBinds() {
        var source = new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("NEXACHAT_E2EE_V1_ACTIVATION_ENABLED", "true"));
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        E2eeProperties properties = binder
                .bind("nexachat.e2ee", Bindable.of(E2eeProperties.class)).get();
        assertThat(properties.isV1ActivationEnabled()).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(E2eeProperties.class)
    static class PropertiesConfiguration {
    }
}

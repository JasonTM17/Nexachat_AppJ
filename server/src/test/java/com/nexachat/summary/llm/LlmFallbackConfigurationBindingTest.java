package com.nexachat.summary.llm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the YAML prefix used by {@link LlmFallbackProperties}. The original
 * defect nested {@code llm-fallback} under {@code nexachat.summary.llm}, so the
 * binder never saw the YAML keys and the Java defaults silently masked the
 * mistake. This test reads the real {@code application.yml}, proves every
 * fallback key resolves under the class prefix, and binds one sentinel value so
 * a future nesting regression cannot pass on Java defaults alone.
 */
class LlmFallbackConfigurationBindingTest {

    private static final String PREFIX = "nexachat.summary.llm-fallback";

    @Test
    void fallbackPropertiesBindFromTheRealApplicationYaml() throws IOException {
        Map<String, Object> yaml = flattenedApplicationYaml();

        assertThat(yaml).containsKeys(
                PREFIX + ".enabled",
                PREFIX + ".base-url",
                PREFIX + ".model",
                PREFIX + ".api-key",
                PREFIX + ".timeout-ms");

        yaml.put(PREFIX + ".model", "yaml-bound-model");

        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("application.yml", yaml));
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));

        LlmFallbackProperties properties = binder
                .bind(PREFIX, Bindable.of(LlmFallbackProperties.class))
                .orElseThrow(() -> new AssertionError("fallback properties did not bind"));

        assertThat(properties.getModel()).isEqualTo("yaml-bound-model");
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.hasApiKey()).isFalse();
        assertThat(properties.getTimeoutMs()).isEqualTo(30_000);
    }

    private static Map<String, Object> flattenedApplicationYaml() throws IOException {
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
        assertThat(application).isInstanceOf(EnumerablePropertySource.class);
        EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) application;
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : enumerable.getPropertyNames()) {
            values.put(name, enumerable.getProperty(name));
        }
        return values;
    }
}

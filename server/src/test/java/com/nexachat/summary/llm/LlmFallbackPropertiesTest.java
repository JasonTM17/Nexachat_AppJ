package com.nexachat.summary.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmFallbackPropertiesTest {

    @Test
    void defaultConfigurationStaysDisabledWithoutAnOperatorKey() {
        LlmFallbackProperties properties = new LlmFallbackProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.hasApiKey()).isFalse();
        assertThat(properties.isUsable()).isFalse();
        assertThat(properties.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.getModel()).isEqualTo("deepseek-flash");
    }

    @Test
    void apiKeyCannotBeSerializedOrIncludedInTheDefaultObjectDescription() throws Exception {
        LlmFallbackProperties properties = configured("https://api.deepseek.com");

        assertThat(new ObjectMapper().writeValueAsString(properties)).doesNotContain("test-only-key", "apiKey");
        assertThat(properties.toString()).doesNotContain("test-only-key");
    }

    @Test
    void buildsCompatibleEndpointAndRejectsCredentialOrPlainRemoteUrls() {
        LlmFallbackProperties properties = configured("https://api.deepseek.com/v1/");
        assertThat(properties.endpoint()).hasToString("https://api.deepseek.com/v1/chat/completions");
        assertThat(properties.isUsable()).isTrue();

        properties.setBaseUrl("http://api.deepseek.com");
        assertThat(properties.isUsable()).isFalse();
        properties.setBaseUrl("https://user:pass@api.deepseek.com");
        assertThat(properties.isUsable()).isFalse();
        properties.setBaseUrl("https://api.deepseek.com?key=private");
        assertThat(properties.isUsable()).isFalse();
    }

    @Test
    void permitsPlainHttpOnlyForLoopbackFakeServers() {
        LlmFallbackProperties properties = configured("http://127.0.0.1:54321");

        assertThat(properties.endpoint()).hasToString("http://127.0.0.1:54321/chat/completions");
        assertThat(properties.isUsable()).isTrue();
    }

    @Test
    void unusableWhenDisabledEvenWithAKey() {
        LlmFallbackProperties properties = configured("https://api.deepseek.com");
        properties.setEnabled(false);

        assertThat(properties.isUsable()).isFalse();
    }

    private static LlmFallbackProperties configured(String baseUrl) {
        LlmFallbackProperties properties = new LlmFallbackProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-only-key");
        properties.setBaseUrl(baseUrl);
        properties.setModel("deepseek-flash");
        properties.setTimeoutMs(5_000);
        return properties;
    }
}

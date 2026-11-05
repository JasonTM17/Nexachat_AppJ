package com.nexachat.summary.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPropertiesTest {

    @Test
    void defaultConfigurationStaysDisabledWithoutAnOperatorKey() {
        LlmProperties properties = new LlmProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.hasApiKey()).isFalse();
        assertThat(properties.isUsable()).isFalse();
    }

    @Test
    void apiKeyCannotBeSerializedOrIncludedInTheDefaultObjectDescription() throws Exception {
        LlmProperties properties = configured("https://api.example.com");

        assertThat(new ObjectMapper().writeValueAsString(properties)).doesNotContain("test-only-key", "apiKey");
        assertThat(properties.toString()).doesNotContain("test-only-key");
    }

    @Test
    void buildsCompatibleEndpointAndRejectsCredentialOrPlainRemoteUrls() {
        LlmProperties properties = configured("https://api.example.com/v1/");
        assertThat(properties.endpoint()).hasToString("https://api.example.com/v1/chat/completions");
        assertThat(properties.isUsable()).isTrue();

        properties.setBaseUrl("http://api.example.com");
        assertThat(properties.isUsable()).isFalse();
        properties.setBaseUrl("https://user:pass@api.example.com");
        assertThat(properties.isUsable()).isFalse();
        properties.setBaseUrl("https://api.example.com?key=private");
        assertThat(properties.isUsable()).isFalse();
    }

    @Test
    void permitsPlainHttpOnlyForLoopbackFakeServers() {
        LlmProperties properties = configured("http://127.0.0.1:54321");

        assertThat(properties.endpoint()).hasToString("http://127.0.0.1:54321/chat/completions");
        assertThat(properties.isUsable()).isTrue();
    }

    private static LlmProperties configured(String baseUrl) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-only-key");
        properties.setBaseUrl(baseUrl);
        return properties;
    }
}

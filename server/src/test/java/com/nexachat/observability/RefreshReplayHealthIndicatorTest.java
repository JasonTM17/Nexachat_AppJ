package com.nexachat.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.RefreshReplayCipher;
import com.nexachat.auth.RefreshReplayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshReplayHealthIndicatorTest {

    @Test
    void nonProductionCanUseLegacyStrictRotationWhenKeyIsMissingOrMalformed() {
        for (String key : new String[]{"", "not-base64"}) {
            var health = indicator(key, false).health();

            assertThat(health.getStatus().getCode()).isEqualTo("UP");
            assertThat(health.getDetails()).containsEntry("mode", "legacy-strict-rotation");
        }
    }

    @Test
    void productionReadinessFailsWhenKeyIsMissingOrMalformed() {
        assertThat(indicator("", true).health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(indicator("not-base64", true).health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void productionReadinessIsUpWithAValidKey() {
        var health = indicator(validKey(), true).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("mode", "encrypted-replay");
    }

    private static RefreshReplayHealthIndicator indicator(String key, boolean production) {
        RefreshReplayProperties properties = new RefreshReplayProperties();
        properties.setKey(key);
        MockEnvironment environment = new MockEnvironment();
        if (production) {
            environment.setActiveProfiles("prod");
        }
        return new RefreshReplayHealthIndicator(
                new RefreshReplayCipher(new ObjectMapper(), properties), environment);
    }

    private static String validKey() {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) index;
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}

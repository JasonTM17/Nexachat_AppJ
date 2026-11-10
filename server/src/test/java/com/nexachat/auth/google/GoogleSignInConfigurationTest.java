package com.nexachat.auth.google;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup behavior of the Google decoder: fail closed when misconfigured, never fetch when off. */
class GoogleSignInConfigurationTest {

    @Test
    void disabledFeatureDoesNotRequireAClientId() {
        assertThatCode(() -> GoogleSignInConfiguration.decoderFor(
                new GoogleSignInProperties(false, "", null, 300, java.util.List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledFeatureWithoutAClientIdFailsStartup() {
        assertThatThrownBy(() -> GoogleSignInConfiguration.decoderFor(
                new GoogleSignInProperties(true, " ", "https://example.invalid/certs", 300, java.util.List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("web-client-id");
    }

    @Test
    void enabledFeatureBuildsARealDecoderWithoutContactingTheNetwork() {
        JwtDecoder decoder = GoogleSignInConfiguration.decoderFor(
                new GoogleSignInProperties(true, GoogleTestKeys.AUDIENCE,
                        "http://127.0.0.1:1/certs", 300, java.util.List.of()));

        assertThat(decoder).isNotNull();
    }

    @Test
    void defaultsApplyWhenPropertiesAreOmitted() {
        GoogleSignInProperties properties = new GoogleSignInProperties(false, "", "", 0, java.util.List.of());

        assertThat(properties.jwksUri()).isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
        assertThat(properties.nonceTtlSeconds()).isEqualTo(300);
    }
}

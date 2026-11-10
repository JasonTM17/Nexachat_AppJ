package com.nexachat.auth.google;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the Google ID-token decoder from the configured JWKS URI.
 *
 * <p>Nimbus' {@code RemoteJWKSet} fetches lazily and caches the key set,
 * refreshing it when Google rotates to an unknown key ID, so the application
 * starts without network access and tests can serve a local key set.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleSignInProperties.class)
public class GoogleSignInConfiguration {

    @Bean
    public JwtDecoder googleIdTokenDecoder(GoogleSignInProperties properties) {
        return decoderFor(properties);
    }

    static JwtDecoder decoderFor(GoogleSignInProperties properties) {
        if (!properties.enabled()) {
            // Disabled features must not reach the network. The verifier is never
            // called because every endpoint answers 404 first.
            return token -> {
                throw new JwtException("Google sign-in is disabled");
            };
        }
        if (properties.webClientId() == null || properties.webClientId().isBlank()) {
            throw new IllegalStateException(
                    "nexachat.google.signin.web-client-id is required when Google sign-in is enabled");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(GoogleIdTokenValidators.forAudience(
                properties.webClientId(), properties.androidClientIds()));
        return decoder;
    }
}

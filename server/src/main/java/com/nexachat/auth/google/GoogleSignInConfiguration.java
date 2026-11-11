package com.nexachat.auth.google;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

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

    private static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /**
     * Total deadline for one JWKS HTTP exchange (request sent until the body
     * is fully read). Unlike a per-read timeout this cannot be extended by a
     * stalled body or by the response cleanup reading the stream again.
     */
    static final Duration JWKS_REQUEST_TIMEOUT = Duration.ofSeconds(5);

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
        if (properties.androidClientIds().isEmpty()) {
            throw new IllegalStateException(
                    "nexachat.google.signin.android-client-ids is required when Google sign-in is enabled");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(jwksRestOperations())
                .build();
        decoder.setJwtValidator(GoogleIdTokenValidators.forAudience(
                properties.webClientId(), properties.androidClientIds()));
        return decoder;
    }

    static RestOperations jwksRestOperations() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(JWKS_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(JWKS_REQUEST_TIMEOUT);
        return new RestTemplate(factory);
    }
}

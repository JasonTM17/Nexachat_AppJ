package com.nexachat.config;

import com.nexachat.auth.AuthSecurityErrorHandler;
import com.nexachat.auth.BcryptBoundedPasswordEncoder;
import com.nexachat.auth.JwtAuthFilter;
import com.nexachat.auth.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT security for the REST API (plan T13/T14, §13):
 * <ul>
 *   <li>CSRF disabled — token-in-header API, no cookie session to forge.</li>
 *   <li>permitAll: POST /auth/register|login|refresh, /actuator/health, /error;
 *       every other request requires a valid Bearer token.</li>
 *   <li>{@link JwtAuthFilter} runs before {@link UsernamePasswordAuthenticationFilter};
 *       failures are rendered by {@link AuthSecurityErrorHandler} as the standard
 *       error envelope (401 UNAUTHORIZED / TOKEN_EXPIRED / TOKEN_INVALID, 403 FORBIDDEN).</li>
 *   <li>bcrypt cost 12 (FR/NFR-3: ≥10, spec requires 12).</li>
 *   <li>CORS is an explicit per-environment allow-list; credentials are never
 *       enabled, so no origin can ride a logged-in browser session.</li>
 *   <li>Operator telemetry and non-health Actuator endpoints are restricted
 *       to the configured admin phones.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AuthSecurityErrorHandler securityErrorHandler;
    private final SecurityHeadersFilter securityHeadersFilter;
    private final RagStatsAuthorizationManager ragStatsAuthorizationManager;
    private final SecurityProperties securityProperties;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          AuthSecurityErrorHandler securityErrorHandler,
                          SecurityHeadersFilter securityHeadersFilter,
                          RagStatsAuthorizationManager ragStatsAuthorizationManager,
                          SecurityProperties securityProperties) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.securityErrorHandler = securityErrorHandler;
        this.securityHeadersFilter = securityHeadersFilter;
        this.ragStatsAuthorizationManager = ragStatsAuthorizationManager;
        this.securityProperties = securityProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BcryptBoundedPasswordEncoder(new BCryptPasswordEncoder(12));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/google",
                                "/api/v1/auth/google/nonce").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        // WS handshake stays unauthenticated at HTTP level: the
                        // JWT travels in the STOMP CONNECT header and is checked
                        // by StompAuthChannelInterceptor, never by this filter
                        // chain (plan §11.1/§13 — no token-in-URL).
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        // Aggregate RAG counters are operator telemetry, not
                        // user data: only the configured admin phones may read.
                        .requestMatchers("/api/v1/rag/stats").access(ragStatsAuthorizationManager)
                        .requestMatchers("/actuator/**").access(ragStatsAuthorizationManager)
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // After HeaderWriterFilter so the auth-only no-store policy can
                // override the global cache header for credential responses.
                .addFilterAfter(securityHeadersFilter, HeaderWriterFilter.class);
        return http.build();
    }

    /**
     * CORS allow-list from {@code nexachat.security.cors-allowed-origins}
     * (env {@code NEXACHAT_CORS_ALLOWED_ORIGINS}, comma-separated). The secure
     * default is an empty list — no cross-origin caller admitted — which is
     * correct for a native-client deployment that sends no {@code Origin}.
     *
     * <p>Credentials are never enabled and the wildcard origin is rejected at
     * startup: a browser could otherwise replay a logged-in session from any
     * site.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> allowedOrigins = securityProperties.corsAllowedOrigins();
        if (allowedOrigins.contains("*")) {
            throw new IllegalStateException(
                    "nexachat.security.cors-allowed-origins must list exact origins; '*' is not allowed");
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

package com.nexachat.config;

import com.nexachat.auth.AuthSecurityErrorHandler;
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
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AuthSecurityErrorHandler securityErrorHandler;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, AuthSecurityErrorHandler securityErrorHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.securityErrorHandler = securityErrorHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
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
                                "/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Dev-grade CORS: the client is a native Android app (browsers are not the
     * target), so any origin may call the API without credentials. Restrict
     * {@code allowedOriginPatterns} per environment before any public deploy.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
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

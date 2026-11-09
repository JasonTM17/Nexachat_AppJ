package com.nexachat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds {@code nexachat.security.*}:
 * <ul>
 *   <li>{@code cors-allowed-origins} — exact origins admitted for cross-origin
 *       REST and WebSocket requests. Empty means no cross-origin caller is
 *       admitted; the secure default for a native-client deployment, which
 *       sends no {@code Origin} header at all.</li>
 *   <li>{@code admin-phones} — E.164 numbers allowed to read operator
 *       telemetry such as {@code /api/v1/rag/stats} and non-health Actuator
 *       endpoints. Normalized on use.</li>
 * </ul>
 *
 * <p>Both lists are environment-driven ({@code NEXACHAT_CORS_ALLOWED_ORIGINS},
 * {@code NEXACHAT_ADMIN_PHONES}) so no origin or account is hard-coded.
 */
@ConfigurationProperties(prefix = "nexachat.security")
public record SecurityProperties(List<String> corsAllowedOrigins, List<String> adminPhones) {

    public SecurityProperties {
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        adminPhones = adminPhones == null ? List.of() : List.copyOf(adminPhones);
    }

    public boolean isAllowedOrigin(String origin) {
        return origin != null && corsAllowedOrigins.contains(origin);
    }
}

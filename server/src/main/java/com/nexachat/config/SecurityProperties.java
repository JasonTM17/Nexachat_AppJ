package com.nexachat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

/**
 * Binds {@code nexachat.security.*}:
 * <ul>
 *   <li>{@code cors-allowed-origins} — exact origins admitted for the two
 *       public account-deletion REST routes and WebSocket requests. Empty means
 *       no cross-origin browser caller is admitted.</li>
 *   <li>{@code admin-phones} — E.164 numbers allowed to read operator
 *       telemetry such as {@code /api/v1/rag/stats} and non-health Actuator
 *       endpoints. Normalized on use.</li>
 *   <li>{@code admin-emails} — verified email addresses with the same operator
 *       access as {@code admin-phones}, for accounts that have no phone number.</li>
 *   <li>{@code trusted-proxy-cidrs} — CIDRs or exact IPs of reverse proxies
 *       that may supply the client-IP header. Empty (the default) means per-IP
 *       rate limits always use the socket peer and ignore forwarded headers.</li>
 *   <li>{@code client-ip-header} — header carrying the client address when
 *       the peer is a trusted proxy. Defaults to {@code X-Forwarded-For}, which
 *       is read from the right; set it to a single-value header such as
 *       {@code CF-Connecting-IP} only when the edge overwrites that header.</li>
 * </ul>
 *
 * <p>List values are environment-driven ({@code NEXACHAT_CORS_ALLOWED_ORIGINS},
 * {@code NEXACHAT_ADMIN_PHONES}, {@code NEXACHAT_TRUSTED_PROXY_CIDRS}) and the
 * header name by {@code NEXACHAT_CLIENT_IP_HEADER}, so nothing is hard-coded.
 */
@ConfigurationProperties(prefix = "nexachat.security")
public record SecurityProperties(
        List<String> corsAllowedOrigins,
        List<String> adminPhones,
        List<String> trustedProxyCidrs,
        String clientIpHeader,
        List<String> adminEmails) {

    public SecurityProperties(List<String> corsAllowedOrigins, List<String> adminPhones,
                              List<String> trustedProxyCidrs, String clientIpHeader) {
        this(corsAllowedOrigins, adminPhones, trustedProxyCidrs, clientIpHeader, List.of());
    }

    @ConstructorBinding
    public SecurityProperties {
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        adminPhones = adminPhones == null ? List.of() : List.copyOf(adminPhones);
        trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
        adminEmails = adminEmails == null ? List.of() : List.copyOf(adminEmails);
        clientIpHeader = clientIpHeader == null || clientIpHeader.isBlank()
                ? ClientIpResolver.DEFAULT_CLIENT_IP_HEADER
                : clientIpHeader.strip();
    }

    public boolean isAllowedOrigin(String origin) {
        return origin != null && corsAllowedOrigins.contains(origin);
    }
}

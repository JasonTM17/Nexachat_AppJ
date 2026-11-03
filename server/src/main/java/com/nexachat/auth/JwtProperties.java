package com.nexachat.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code nexachat.jwt.*} from application.yml:
 * <ul>
 *   <li>{@code secret} — HS256 symmetric key material, must be ≥ 32 bytes (T14)</li>
 *   <li>{@code access-ttl-seconds} — access token lifetime, 900 (15 min)</li>
 *   <li>{@code refresh-ttl-days} — refresh token lifetime, 14 days</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "nexachat.jwt")
public record JwtProperties(String secret, long accessTtlSeconds, long refreshTtlDays) {
}

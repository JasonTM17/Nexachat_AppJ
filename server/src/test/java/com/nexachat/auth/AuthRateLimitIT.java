package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Credential-endpoint rate limiting must hold per IP <em>and</em> per phone, so
 * a distributed guess against one account is bounded even when the client IP
 * changes, and a burst of distinct phones is bounded per source address.
 */
class AuthRateLimitIT extends SecurityITBase {

    private static final String PHONE = ItPhoneRanges.nextPhone(AuthRateLimitIT.class);

    @Test
    void loginIsLimitedPerPhoneEvenWhenTheClientIpChanges() throws Exception {
        register(PHONE);

        // The per-phone bucket holds 20 attempts per 5 minutes; each attempt
        // comes from a different address so the per-IP bucket never trips.
        for (int attempt = 0; attempt < 20; attempt++) {
            postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", "wrongpass9"),
                            null, remoteAddr("10.20.0." + attempt))
                    .andExpect(status().isUnauthorized());
        }

        postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", "wrongpass9"),
                        null, remoteAddr("10.20.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").exists());
    }

    @Test
    void loginIsStillLimitedPerIp() throws Exception {
        register(PHONE);

        for (int attempt = 0; attempt < 10; attempt++) {
            postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", "wrongpass9"),
                            null, remoteAddr("10.21.0.1"))
                    .andExpect(status().isUnauthorized());
        }

        postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", "wrongpass9"),
                        null, remoteAddr("10.21.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void distinctPhonesAreNotBlockedByThePerPhoneLimit() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            String phone = ItPhoneRanges.nextPhone(AuthRateLimitIT.class);
            postJson("/api/v1/auth/login", Map.of("phone", phone, "password", "wrongpass9"),
                            null, remoteAddr("10.22.0." + attempt))
                    .andExpect(status().isUnauthorized());
        }
    }
}

package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Credential-endpoint rate limiting must hold on three layers: per source
 * address, per claimed credential, and one shared budget per ACCOUNT. A
 * distributed guess against a single account is bounded by the account budget
 * even when the client IP rotates, a burst of distinct phones is bounded per
 * source address, and an unknown identifier keeps the uniform per-credential
 * contract (never the account-specific lockout code).
 */
class AuthRateLimitIT extends SecurityITBase {

    private static final String PHONE = ItPhoneRanges.nextPhone(AuthRateLimitIT.class);

    private static final String WRONG_PASSWORD = "wrongpass9";

    @Test
    void loginIsLockedPerAccountEvenWhenTheClientIpChanges() throws Exception {
        register(PHONE);

        // The shared account budget holds 10 failures per 15 minutes; each
        // attempt comes from a different address, so neither the per-IP bucket
        // (10/5 min) nor the per-credential budget (20/5 min) trips first.
        for (int attempt = 0; attempt < 10; attempt++) {
            postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", WRONG_PASSWORD),
                            null, remoteAddr("10.20.0." + attempt))
                    .andExpect(status().isUnauthorized());
        }

        postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", WRONG_PASSWORD),
                        null, remoteAddr("10.20.1.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("SIGN_IN_LOCKED"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").exists())
                .andExpect(jsonPath("$.error.details.scope").value("account"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void loginIsStillLimitedPerIp() throws Exception {
        register(PHONE);

        for (int attempt = 0; attempt < 10; attempt++) {
            postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", WRONG_PASSWORD),
                            null, remoteAddr("10.21.0.1"))
                    .andExpect(status().isUnauthorized());
        }

        postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", WRONG_PASSWORD),
                        null, remoteAddr("10.21.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void loginWithAnUnknownPhoneKeepsTheUniformPerCredentialBudget() throws Exception {
        String unknown = ItPhoneRanges.nextPhone(AuthRateLimitIT.class);

        // No account row resolves, so only the per-claimed-credential budget
        // (20/5 min) applies: the account lockout code never appears for an
        // unknown identifier, and below exhaustion the 401 stays uniform.
        for (int attempt = 0; attempt < 20; attempt++) {
            postJson("/api/v1/auth/login", Map.of("phone", unknown, "password", WRONG_PASSWORD),
                            null, remoteAddr("10.23.0." + attempt))
                    .andExpect(status().isUnauthorized());
        }

        postJson("/api/v1/auth/login", Map.of("phone", unknown, "password", WRONG_PASSWORD),
                        null, remoteAddr("10.23.1.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void aSuccessfulLoginRestoresTheAccountBudget() throws Exception {
        register(PHONE);

        // Nine failures leave one charge in the account budget.
        for (int attempt = 0; attempt < 9; attempt++) {
            postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", WRONG_PASSWORD),
                            null, remoteAddr("10.24.0." + attempt))
                    .andExpect(status().isUnauthorized());
        }

        // The owner signs in successfully, which resets the account budget.
        postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", "secret123"),
                        null, remoteAddr("10.24.1.1"))
                .andExpect(status().isOk());

        // Without the reset, the second failure below would be the budget's
        // 11th charge and would answer SIGN_IN_LOCKED. With it, the account
        // admits a full new budget: ten more 401s, the last of which is also
        // the 20th per-credential attempt — still inside that 20/5 min cap,
        // and every request comes from a fresh address so no per-IP bucket
        // interferes.
        for (int attempt = 0; attempt < 10; attempt++) {
            postJson("/api/v1/auth/login", Map.of("phone", PHONE, "password", WRONG_PASSWORD),
                            null, remoteAddr("10.24.2." + attempt))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void distinctPhonesAreNotBlockedByThePerPhoneLimit() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            String phone = ItPhoneRanges.nextPhone(AuthRateLimitIT.class);
            postJson("/api/v1/auth/login", Map.of("phone", phone, "password", WRONG_PASSWORD),
                            null, remoteAddr("10.22.0." + attempt))
                    .andExpect(status().isUnauthorized());
        }
    }
}

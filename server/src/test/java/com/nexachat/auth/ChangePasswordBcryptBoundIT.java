package com.nexachat.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change-password must reject a 73-byte current password when the stored secret is exactly 72
 * bytes (bcrypt prefix match without the bounded encoder) and must reject over-long new passwords.
 */
class ChangePasswordBcryptBoundIT extends SecurityITBase {

    @Test
    void changePassword_seventyThreeByteOldPassword_rejectsBcryptPrefixMatch() throws Exception {
        String password72 = "a".repeat(72);
        Registered user = register("+84905551301", password72);
        String token = accessTokenIssuedBeforeRevocation(user.userId());
        String overLongOld = "a".repeat(73);

        patchWithAuth("/api/v1/auth/password", Map.of(
                        "oldPassword", overLongOld,
                        "newPassword", "newsecret456"),
                token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WRONG_PASSWORD"));

        postJson("/api/v1/auth/login", Map.of("phone", "+84905551301", "password", password72))
                .andExpect(status().isOk());
    }

    @Test
    void changePassword_overLongNewPassword_returnsValidationFailed() throws Exception {
        Registered user = register("+84905551302");
        String token = accessTokenIssuedBeforeRevocation(user.userId());
        String overLongNew = "b".repeat(73);

        patchWithAuth("/api/v1/auth/password", Map.of(
                        "oldPassword", "secret123",
                        "newPassword", overLongNew),
                token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}

package com.nexachat.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change-password must not 500 when the client sends an over-long current password.
 */
class ChangePasswordBcryptBoundIT extends SecurityITBase {

    @Test
    void changePassword_overLongOldPassword_matchesWrongPasswordResponse() throws Exception {
        Registered user = register("+84905551301");
        String token = accessTokenIssuedBeforeRevocation(user.userId());
        String overLongOld = "a".repeat(73);

        patchWithAuth("/api/v1/auth/password", Map.of(
                        "oldPassword", overLongOld,
                        "newPassword", "newsecret456"),
                token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WRONG_PASSWORD"));

        patchWithAuth("/api/v1/auth/password", Map.of(
                        "oldPassword", "definitely-wrong",
                        "newPassword", "newsecret456"),
                token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WRONG_PASSWORD"));
    }
}

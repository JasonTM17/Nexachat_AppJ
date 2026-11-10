package com.nexachat.auth;

import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void bcryptRejectsPast72BytesAndThePolicyRejectsThatSecret() {
        String full = "é".repeat(40);
        byte[] utf8 = full.getBytes(StandardCharsets.UTF_8);
        assertThat(utf8.length).isGreaterThan(PasswordPolicy.MAX_UTF8_BYTES);

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        assertThatThrownBy(() -> encoder.encode(full))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72 bytes");

        assertThatThrownBy(() -> PasswordPolicy.requireRegisterable(full))
                .isInstanceOf(ApiException.class);
        String prefix = new String(Arrays.copyOf(utf8, PasswordPolicy.MAX_UTF8_BYTES), StandardCharsets.UTF_8);
        assertThat(PasswordPolicy.fitsBcrypt(prefix)).isTrue();
        assertThat(encoder.matches(prefix, encoder.encode(prefix))).isTrue();
        assertThat(PasswordPolicy.fitsBcrypt("secret123\u0000more")).isFalse();
    }
}

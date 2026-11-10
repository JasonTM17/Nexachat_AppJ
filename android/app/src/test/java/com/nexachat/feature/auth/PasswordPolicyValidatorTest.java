package com.nexachat.feature.auth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class PasswordPolicyValidatorTest {

    @Test
    public void acceptsPasswordAt72Utf8ByteBoundary() {
        String password = "a".repeat(72);
        assertEquals72Bytes(password);
        assertTrue(PasswordPolicyValidator.isAcceptable(password));
        assertFalse(PasswordPolicyValidator.isTooLong(password));
    }

    @Test
    public void rejectsPasswordBeyond72Utf8BytesClientSide() {
        String ascii73 = "a".repeat(73);
        assertFalse(PasswordPolicyValidator.isAcceptable(ascii73));
        assertTrue(PasswordPolicyValidator.isTooLong(ascii73));

        String multibyte = "é".repeat(37);
        assertTrue(multibyte.getBytes(StandardCharsets.UTF_8).length > 72);
        assertTrue(PasswordPolicyValidator.isTooLong(multibyte));
    }

  private static void assertEquals72Bytes(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length != 72) {
      throw new AssertionError("expected 72 UTF-8 bytes");
    }
  }
}

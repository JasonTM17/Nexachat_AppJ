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
        String password = "é".repeat(25);
        assertTrue(password.getBytes(StandardCharsets.UTF_8).length > 72);
        assertFalse(PasswordPolicyValidator.isAcceptable(password));
        assertTrue(PasswordPolicyValidator.isTooLong(password));
    }

  private static void assertEquals72Bytes(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length != 72) {
      throw new AssertionError("expected 72 UTF-8 bytes");
    }
  }
}

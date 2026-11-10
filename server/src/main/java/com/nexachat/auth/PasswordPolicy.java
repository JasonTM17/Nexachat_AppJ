package com.nexachat.auth;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.nio.charset.StandardCharsets;

/**
 * Bcrypt only consumes the first 72 bytes and, in the original crypt, stops at
 * the first NUL. Passwords outside that input are rejected instead of being
 * silently truncated into a shorter equivalent secret.
 */
public final class PasswordPolicy {

    /** bcrypt input ceiling. Characters and bytes differ for non-ASCII secrets. */
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    /** Register and password-change: a secret bcrypt would not hash in full. */
    public static void requireRegisterable(String password) {
        if (!fitsBcrypt(password)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "password must be at most 72 UTF-8 bytes and must not contain NUL");
        }
    }

    /**
     * Login candidate. Over-long or NUL-containing secrets are not compared
     * against the stored hash, so a 72-byte prefix cannot unlock a longer password.
     */
    public static boolean fitsBcrypt(String password) {
        if (password == null || password.indexOf('\0') >= 0) {
            return false;
        }
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}

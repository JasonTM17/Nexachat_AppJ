package com.nexachat.feature.auth;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PasswordResetOtpSanitizerTest {

    @Test
    public void sanitizeKeepsSixDigitsFromPaste() {
        assertEquals("123456", PasswordResetOtpSanitizer.sanitize("12 34-56"));
        assertTrue(PasswordResetOtpSanitizer.isComplete("123456"));
        assertFalse(PasswordResetOtpSanitizer.isComplete("12345"));
    }
}

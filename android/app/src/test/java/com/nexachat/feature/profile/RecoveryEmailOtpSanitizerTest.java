package com.nexachat.feature.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecoveryEmailOtpSanitizerTest {

    @Test
    public void pasteKeepsDigitsOnlyAndCapsAtSix() {
        assertEquals("123456", RecoveryEmailOtpSanitizer.sanitize("12a34-56 78"));
    }

    @Test
    public void completeWhenSixDigits() {
        assertTrue(RecoveryEmailOtpSanitizer.isComplete("123456"));
        assertFalse(RecoveryEmailOtpSanitizer.isComplete("12345"));
    }
}

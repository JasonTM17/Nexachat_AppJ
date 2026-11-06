package com.nexachat.feature.contacts;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SelfContactGuardTest {

    @Test
    public void matchingNationalAndInternationalFormsAreTheSameAccount() {
        assertTrue(SelfContactGuard.isOwnPhone("0912 345 678", "+84912345678"));
        assertTrue(SelfContactGuard.isOwnPhone("+84912345678", "0912345678"));
    }

    @Test
    public void aDifferentNumberOrBlankInputIsNotSelf() {
        assertFalse(SelfContactGuard.isOwnPhone("0987654321", "+84912345678"));
        assertFalse(SelfContactGuard.isOwnPhone("   ", "+84912345678"));
        assertFalse(SelfContactGuard.isOwnPhone(null, "+84912345678"));
        assertFalse(SelfContactGuard.isOwnPhone("+84912345678", null));
    }
}

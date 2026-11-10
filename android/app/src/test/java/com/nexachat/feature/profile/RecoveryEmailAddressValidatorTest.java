package com.nexachat.feature.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecoveryEmailAddressValidatorTest {

    @Test
    public void acceptsSimpleAddress() {
        assertTrue(RecoveryEmailAddressValidator.isValid("user@example.com"));
    }

    @Test
    public void rejectsMissingAt() {
        assertFalse(RecoveryEmailAddressValidator.isValid("user.example.com"));
    }
}

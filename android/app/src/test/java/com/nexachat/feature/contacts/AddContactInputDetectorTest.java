package com.nexachat.feature.contacts;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AddContactInputDetectorTest {

    @Test
    public void detectsAtSign() {
        assertTrue(AddContactInputDetector.containsEmailHint("a@b.co"));
        assertFalse(AddContactInputDetector.containsEmailHint("+84123"));
    }

    @Test
    public void classifiesValidEmail() {
        assertEquals(
                AddContactInputDetector.Kind.EMAIL,
                AddContactInputDetector.classify("User@Example.COM"));
    }

    @Test
    public void rejectsInvalidEmail() {
        assertEquals(AddContactInputDetector.Kind.INVALID, AddContactInputDetector.classify("bad@"));
    }

    @Test
    public void classifiesPhoneInput() {
        assertEquals(AddContactInputDetector.Kind.PHONE, AddContactInputDetector.classify("+84912345678"));
    }

    @Test
    public void normalizesEmailToLowerCase() {
        assertEquals("user@example.com", AddContactInputDetector.normalizeEmail(" User@Example.COM "));
    }
}

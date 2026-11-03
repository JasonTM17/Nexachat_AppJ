package com.nexachat.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneNormalizerTest {

    @Test
    void normalizesVietnameseLocalNumber() {
        assertEquals("+84901234567", PhoneNormalizer.toE164("0901234567"));
    }

    @Test
    void keepsAlreadyFormattedE164() {
        assertEquals("+84901234567", PhoneNormalizer.toE164("+84901234567"));
    }

    @Test
    void normalizesCountryCodeWithoutPlus() {
        assertEquals("+84901234567", PhoneNormalizer.toE164("84 901 234 567"));
    }

    @Test
    void ignoresSeparators() {
        assertEquals("+84901234567", PhoneNormalizer.toE164("090-123-4567"));
        assertEquals("+84901234567", PhoneNormalizer.toE164("090 123 4567"));
    }

    @Test
    void normalizesExplicitInternationalNumber() {
        assertEquals("+16502530000", PhoneNormalizer.toE164("+1 650 253 0000"));
    }

    @Test
    void usesExplicitRegionWhenProvided() {
        assertEquals("+84901234567", PhoneNormalizer.toE164("0901234567", "VN"));
        assertEquals("+84901234567", PhoneNormalizer.toE164("+84901234567", "US"));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNormalizer.toE164(null));
        assertThrows(IllegalArgumentException.class, () -> PhoneNormalizer.toE164(null, "VN"));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNormalizer.toE164("   "));
    }

    @Test
    void rejectsTooShortOrWrongRegion() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNormalizer.toE164("123"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNormalizer.toE164("0901234567", "US"));
    }

    @Test
    void validatesValidNumbers() {
        assertTrue(PhoneNormalizer.isValidPhone("0901234567", "VN"));
        assertTrue(PhoneNormalizer.isValidPhone("+84901234567", "VN"));
        assertTrue(PhoneNormalizer.isValidPhone("84 901 234 567", "VN"));
    }

    @Test
    void validatesInvalidNumbers() {
        assertFalse(PhoneNormalizer.isValidPhone("123", "VN"));
        assertFalse(PhoneNormalizer.isValidPhone(null, "VN"));
        assertFalse(PhoneNormalizer.isValidPhone("", "VN"));
        assertFalse(PhoneNormalizer.isValidPhone("0901234567", "US"));
    }
}

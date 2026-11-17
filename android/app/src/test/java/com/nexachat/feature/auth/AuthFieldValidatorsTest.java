package com.nexachat.feature.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AuthFieldValidatorsTest {

    @Test
    public void normalizeE164_trimsAndStripsCommonPunctuation() {
        assertEquals("+84901234567", AuthFieldValidators.normalizeE164("  +84 901 234-567  "));
        assertEquals("+2025550123", AuthFieldValidators.normalizeE164("(202) 555-0123"));
        assertEquals("+442079460123", AuthFieldValidators.normalizeE164("+44.20.7946.0123"));
    }

    @Test
    public void normalizeE164_convertsLeading00ToPlus() {
        assertEquals("+84901234567", AuthFieldValidators.normalizeE164("0084901234567"));
    }

    @Test
    public void normalizeE164_prependsPlusToBareInternationalDigits() {
        assertEquals("+84901234567", AuthFieldValidators.normalizeE164("84901234567"));
        assertEquals("+12345678", AuthFieldValidators.normalizeE164("12345678"));
        assertEquals("+123456789012345", AuthFieldValidators.normalizeE164("123456789012345"));
        assertEquals(
                "+12025550123",
                AuthFieldValidators.normalizeE164("+1 (202) 555-0123"));
    }

    @Test
    public void normalizeE164_completesVietnameseNationalNumbers() {
        assertEquals("+84901234567", AuthFieldValidators.normalizeE164("0901234567"));
        assertEquals("+84905123456", AuthFieldValidators.normalizeE164(" 0905 123.456 "));
        assertTrue(AuthFieldValidators.isLoginIdentifier(AuthFieldValidators.normalizeLoginIdentifier("0901234567")));
    }

    @Test
    public void normalizeE164_leavesLeadingZeroAndOutOfRangeDigitsAlone() {
        // Only Vietnamese national numbers are completed; anything else that looks national stays invalid.
        assertEquals("0123456789012", AuthFieldValidators.normalizeE164("0123456789012"));
        // Only Vietnamese national numbers are completed; anything else that looks national stays invalid.
        assertEquals("01234567", AuthFieldValidators.normalizeE164("01234567"));
        assertEquals("1234567", AuthFieldValidators.normalizeE164("1234567"));
        assertEquals("1234567890123456", AuthFieldValidators.normalizeE164("1234567890123456"));
        assertEquals("+123", AuthFieldValidators.normalizeE164("00123"));
    }

    @Test
    public void isE164_acceptsValidInternationalNumbers() {
        assertTrue(AuthFieldValidators.isE164("+12025550123"));
        assertTrue(AuthFieldValidators.isE164("+84901234567"));
        assertTrue(AuthFieldValidators.isE164("+442079460123"));
    }

    @Test
    public void isE164_rejectsInvalidShapes() {
        assertFalse(AuthFieldValidators.isE164("84901234567"));
        assertFalse(AuthFieldValidators.isE164("+0123456789"));
        assertFalse(AuthFieldValidators.isE164("+123"));
        assertFalse(AuthFieldValidators.isE164(""));
    }

    @Test
    public void passwordsMatch_requiresExactEquality() {
        assertTrue(AuthFieldValidators.passwordsMatch("secret", "secret"));
        assertFalse(AuthFieldValidators.passwordsMatch("secret", "Secret"));
        assertFalse(AuthFieldValidators.passwordsMatch("secret", "secret "));
    }

    @Test
    public void loginIdentifierAcceptsEmailOrPhoneAndNormalizesEach() {
        assertEquals("jason@gmail.com", AuthFieldValidators.normalizeLoginIdentifier("  Jason@Gmail.COM "));
        assertEquals("+84901234567", AuthFieldValidators.normalizeLoginIdentifier(" +84 901 234-567 "));
        assertTrue(AuthFieldValidators.isLoginIdentifier("jason@gmail.com"));
        assertTrue(AuthFieldValidators.isLoginIdentifier("+84901234567"));
    }

    @Test
    public void loginIdentifierRejectsMalformedInput() {
        assertFalse(AuthFieldValidators.isLoginIdentifier(""));
        assertFalse(AuthFieldValidators.isLoginIdentifier("jason@"));
        assertFalse(AuthFieldValidators.isLoginIdentifier("@gmail.com"));
        assertFalse(AuthFieldValidators.isLoginIdentifier("jason gmail@x.com"));
        assertFalse(AuthFieldValidators.isLoginIdentifier("0901234567"));
    }

    @Test
    public void emailShapesTheServerWouldRejectAreNotSent() {
        assertTrue(AuthFieldValidators.isEmail("a@example.com"));
        assertTrue(AuthFieldValidators.isEmail("first.last+tag@mail.example.com"));
        assertFalse(AuthFieldValidators.isEmail("a,b@example.com"));
        assertFalse(AuthFieldValidators.isEmail("a..b@example.com"));
        assertFalse(AuthFieldValidators.isEmail("a@example..com"));
        assertFalse(AuthFieldValidators.isEmail(".a@example.com"));
        assertFalse(AuthFieldValidators.isEmail("a.@example.com"));
        assertFalse(AuthFieldValidators.isEmail("a@.example.com"));
        assertFalse(AuthFieldValidators.isEmail("a@localhost"));
    }
}

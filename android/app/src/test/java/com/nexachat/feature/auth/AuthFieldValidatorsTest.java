package com.nexachat.feature.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AuthFieldValidatorsTest {

    @Test
    public void normalizeE164_trimsAndStripsCommonPunctuation() {
        assertEquals("+84901234567", AuthFieldValidators.normalizeE164("  +84 901 234-567  "));
        assertEquals("2025550123", AuthFieldValidators.normalizeE164("(202) 555-0123"));
        assertEquals("+442079460123", AuthFieldValidators.normalizeE164("+44.20.7946.0123"));
    }

    @Test
    public void normalizeE164_convertsLeading00ToPlus() {
        assertEquals("+84901234567", AuthFieldValidators.normalizeE164("0084901234567"));
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
}

package com.nexachat.core.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PhoneNormalizerTest {

    @Test
    public void normalizesVietnameseNationalNumberToE164() {
        assertEquals("+84912345678", PhoneNormalizer.toE164(" 0912 345 678 "));
        assertEquals("+84912345678", PhoneNormalizer.toE164(" 0912 345 678 ", "VN"));
    }

    @Test
    public void preservesValidInternationalNumber() {
        assertEquals("+16502530000", PhoneNormalizer.toE164("+1 650-253-0000", "VN"));
    }

    @Test
    public void trimsEmptyInputWithoutInventingDigits() {
        assertEquals("", PhoneNormalizer.toE164(" \t ", "VN"));
    }

    @Test
    public void preservesUnparseableUnicodeInputForServerValidation() {
        assertEquals("xin số 🌱", PhoneNormalizer.toE164("  xin số 🌱  ", "VN"));
    }

    @Test
    public void trimsNbspAndEmSpaceAroundUnparseableInput() {
        assertEquals("xin số", PhoneNormalizer.toE164("\u00A0xin số\u00A0"));
        assertEquals("xin số", PhoneNormalizer.toE164("\u2003xin số\u2003"));
    }

    @Test
    public void invalidDefaultRegionDoesNotCrashOrRewriteInput() {
        assertEquals("0912345678", PhoneNormalizer.toE164("0912345678", "?"));
    }
}

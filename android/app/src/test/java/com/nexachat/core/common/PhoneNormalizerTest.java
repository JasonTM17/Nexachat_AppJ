package com.nexachat.core.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PhoneNormalizerTest {

    @Test
    public void normalizesVietnameseNationalNumberToE164() {
        assertEquals("+84912345678", PhoneNormalizer.INSTANCE.toE164(" 0912 345 678 ", "VN"));
    }

    @Test
    public void preservesValidInternationalNumber() {
        assertEquals("+16502530000", PhoneNormalizer.INSTANCE.toE164("+1 650-253-0000", "VN"));
    }

    @Test
    public void trimsEmptyInputWithoutInventingDigits() {
        assertEquals("", PhoneNormalizer.INSTANCE.toE164(" \t ", "VN"));
    }

    @Test
    public void preservesUnparseableUnicodeInputForServerValidation() {
        assertEquals("xin số 🌱", PhoneNormalizer.INSTANCE.toE164("  xin số 🌱  ", "VN"));
    }

    @Test
    public void invalidDefaultRegionDoesNotCrashOrRewriteInput() {
        assertEquals("0912345678", PhoneNormalizer.INSTANCE.toE164("0912345678", "?"));
    }
}

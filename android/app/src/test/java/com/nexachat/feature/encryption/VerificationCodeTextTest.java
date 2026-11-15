package com.nexachat.feature.encryption;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The on-screen reflow: the canonical NC1 code keeps its single-line form for
 * the clipboard while the row wraps it into four groups per line.
 */
public final class VerificationCodeTextTest {

    private static final String CODE =
            "NC1 0123 4567 89AB CDEF 0123 4567 89AB CDEF"
                    + " 0123 4567 89AB CDEF 0123 4567 89AB CDEF";

    @Test
    public void anNc1CodeReflowsToFourGroupsPerLine() {
        assertEquals("NC1 0123 4567 89AB CDEF\n"
                        + "0123 4567 89AB CDEF\n"
                        + "0123 4567 89AB CDEF\n"
                        + "0123 4567 89AB CDEF",
                VerificationCodeText.lines(CODE));
    }

    @Test
    public void aShortCodeStaysOnOneLine() {
        assertEquals("NC1 0123 4567", VerificationCodeText.lines("NC1 0123 4567"));
    }

    @Test
    public void unexpectedShapesPassThroughUntouched() {
        assertEquals("", VerificationCodeText.lines(null));
        assertEquals("not a code", VerificationCodeText.lines("not a code"));
        assertEquals("NC1 ", VerificationCodeText.lines("NC1 "));
    }
}

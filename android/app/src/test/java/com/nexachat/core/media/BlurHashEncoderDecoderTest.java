package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BlurHashEncoderDecoderTest {

    @Test
    public void decodesTheOfficialWoltReferenceVector() {
        String hash = "LaJHjmVu8_~po#smR+a~xaoLWCRj";

        assertTrue(BlurHashDecoder.isValid(hash));
        assertEquals(32 * 24, BlurHashDecoder.decode(hash, 32, 24).length);
    }

    @Test
    public void solidColorVectorRoundTripsWithinOneChannelValue() {
        int color = 0xff804020;
        int[] pixels = new int[16];
        java.util.Arrays.fill(pixels, color);

        String hash = BlurHashEncoder.encode(4, 4, pixels, 1, 1);
        int[] decoded = BlurHashDecoder.decode(hash, 8, 8);

        assertEquals("00Ew5T", hash);
        for (int decodedColor : decoded) {
            assertTrue(Math.abs(((decodedColor >>> 16) & 0xff) - 128) <= 1);
            assertTrue(Math.abs(((decodedColor >>> 8) & 0xff) - 64) <= 1);
            assertTrue(Math.abs((decodedColor & 0xff) - 32) <= 1);
        }
    }

    @Test
    public void rejectsInvalidLengthsFlagsCharactersAndAcValues() {
        assertFalse(BlurHashDecoder.isValid(null));
        assertFalse(BlurHashDecoder.isValid(""));
        assertFalse(BlurHashDecoder.isValid("0000000"));
        assertFalse(BlurHashDecoder.isValid("00000!"));
        assertFalse(BlurHashDecoder.isValid("~00000"));
        assertFalse(BlurHashDecoder.isValid("1aa000~~"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void decoderRejectsInvalidHashInsteadOfReturningPartialPixels() {
        BlurHashDecoder.decode("0000000", 32, 32);
    }
}

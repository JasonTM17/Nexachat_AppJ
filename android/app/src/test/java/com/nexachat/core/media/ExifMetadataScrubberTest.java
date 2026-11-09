package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public final class ExifMetadataScrubberTest {

    @Test
    public void stripsStandardXmpApp1ContainingGps() throws IOException {
        assertXmpGpsIsRemoved(JpegMetadataFixtures.STANDARD_XMP_PREFIX);
    }

    @Test
    public void stripsExtendedXmpApp1ContainingGps() throws IOException {
        assertXmpGpsIsRemoved(JpegMetadataFixtures.EXTENDED_XMP_PREFIX);
    }

    @Test
    public void preservesJpegOrientationWhenRemovingXmpAndExif() throws IOException {
        byte[] withExifOrientation = jpegWithExifOrientationAndXmp(6);

        byte[] stripped = ExifMetadataScrubber.stripLocationMetadata(
                withExifOrientation, "image/jpeg");

        assertEquals(6, ExifMetadataScrubber.readOrientation(stripped, "image/jpeg"));
        assertFalse(JpegMetadataFixtures.contains(stripped, JpegMetadataFixtures.GPS_TAGS));
        assertFalse(JpegMetadataFixtures.contains(stripped, JpegMetadataFixtures.STANDARD_XMP_PREFIX));
    }

    private static void assertXmpGpsIsRemoved(String prefix) throws IOException {
        byte[] original = JpegMetadataFixtures.jpegWithXmpGps(prefix);
        assertTrue(JpegMetadataFixtures.contains(original, JpegMetadataFixtures.GPS_TAGS));

        byte[] stripped = ExifMetadataScrubber.stripLocationMetadata(original, "image/jpeg");

        assertTrue(stripped.length < original.length);
        assertFalse(JpegMetadataFixtures.contains(stripped, JpegMetadataFixtures.GPS_TAGS));
        assertFalse(JpegMetadataFixtures.contains(stripped, prefix));
        assertEquals(0xff, stripped[0] & 0xff);
        assertEquals(0xd8, stripped[1] & 0xff);
    }

    private static byte[] jpegWithExifOrientationAndXmp(int orientation) throws IOException {
        byte[] xmpJpeg = JpegMetadataFixtures.jpegWithXmpGps(
                JpegMetadataFixtures.STANDARD_XMP_PREFIX);
        byte[] exifPayload = new byte[] {
            'E', 'x', 'i', 'f', 0, 0,
            'I', 'I', 42, 0, 8, 0, 0, 0,
            1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0,
            (byte) orientation, 0, 0, 0, 0, 0, 0, 0
        };
        int segmentLength = exifPayload.length + 2;
        byte[] withExif = new byte[xmpJpeg.length + 4 + exifPayload.length];
        withExif[0] = (byte) 0xff;
        withExif[1] = (byte) 0xd8;
        withExif[2] = (byte) 0xff;
        withExif[3] = (byte) 0xe1;
        withExif[4] = (byte) (segmentLength >>> 8);
        withExif[5] = (byte) segmentLength;
        System.arraycopy(exifPayload, 0, withExif, 6, exifPayload.length);
        System.arraycopy(xmpJpeg, 2, withExif, 6 + exifPayload.length, xmpJpeg.length - 2);
        return withExif;
    }
}

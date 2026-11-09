package com.nexachat.core.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Generated JPEG metadata fixtures; no image content or personal data is checked in. */
final class JpegMetadataFixtures {

    static final String STANDARD_XMP_PREFIX = "http://ns.adobe.com/xap/1.0/\0";
    static final String EXTENDED_XMP_PREFIX = "http://ns.adobe.com/xmp/extension/\0";
    static final String GPS_TAGS = "exif:GPSLatitude=47.6 exif:GPSLongitude=-122.3";

    private JpegMetadataFixtures() { }

    static byte[] jpegWithXmpGps(String prefix) throws IOException {
        ByteArrayOutputStream jpeg = jpegStart();
        appendXmpSegment(jpeg, prefix);
        return jpegFinish(jpeg);
    }

    static byte[] jpegWithStandardAndExtendedXmpGps() throws IOException {
        ByteArrayOutputStream jpeg = jpegStart();
        appendXmpSegment(jpeg, STANDARD_XMP_PREFIX);
        appendXmpSegment(jpeg, EXTENDED_XMP_PREFIX);
        return jpegFinish(jpeg);
    }

    static boolean contains(byte[] bytes, String value) {
        byte[] needle = value.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int start = 0; start <= bytes.length - needle.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (bytes[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static ByteArrayOutputStream jpegStart() throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(new byte[] {(byte) 0xff, (byte) 0xd8});
        return jpeg;
    }

    private static void appendXmpSegment(ByteArrayOutputStream jpeg, String prefix)
            throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(prefix.getBytes(StandardCharsets.US_ASCII));
        payload.write(GPS_TAGS.getBytes(StandardCharsets.US_ASCII));
        int segmentLength = payload.size() + 2;
        jpeg.write(new byte[] {
            (byte) 0xff, (byte) 0xe1,
            (byte) (segmentLength >>> 8), (byte) segmentLength
        });
        payload.writeTo(jpeg);
    }

    private static byte[] jpegFinish(ByteArrayOutputStream jpeg) throws IOException {
        jpeg.write(new byte[] {(byte) 0xff, (byte) 0xda, 0, 2, 1, 2, 3});
        return jpeg.toByteArray();
    }
}

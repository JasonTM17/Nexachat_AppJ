package com.nexachat.core.media;

import androidx.annotation.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Removes EXIF payloads that may contain location while preserving image orientation. */
public final class ExifMetadataScrubber {

    private static final byte[] JPEG_EXIF_PREFIX = {
        'E', 'x', 'i', 'f', 0, 0
    };
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10
    };

    private ExifMetadataScrubber() { }

    @NonNull
    public static byte[] stripLocationMetadata(@NonNull byte[] source, @NonNull String mimeType)
            throws IOException {
        if ("image/jpeg".equals(mimeType)) {
            return stripJpeg(source);
        }
        if ("image/png".equals(mimeType)) {
            return stripPng(source);
        }
        if ("image/webp".equals(mimeType)) {
            return stripWebp(source);
        }
        return source;
    }

    public static int readOrientation(@NonNull byte[] source, @NonNull String mimeType) {
        if ("image/jpeg".equals(mimeType)) {
            return jpegOrientation(source);
        }
        if ("image/png".equals(mimeType)) {
            return pngOrientation(source);
        }
        if ("image/webp".equals(mimeType)) {
            return webpOrientation(source);
        }
        return 1;
    }

    private static byte[] stripJpeg(byte[] source) throws IOException {
        if (source.length < 4 || unsigned(source[0]) != 0xff || unsigned(source[1]) != 0xd8) {
            return source;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
        output.write(source, 0, 2);
        int offset = 2;
        boolean removedExif = false;
        boolean wroteOrientation = false;
        while (offset < source.length) {
            if (unsigned(source[offset]) != 0xff) {
                output.write(source, offset, source.length - offset);
                break;
            }
            int markerStart = offset;
            int markerOffset = offset + 1;
            while (markerOffset < source.length && unsigned(source[markerOffset]) == 0xff) {
                markerOffset++;
            }
            if (markerOffset >= source.length) {
                output.write(source, markerStart, source.length - markerStart);
                break;
            }
            int marker = unsigned(source[markerOffset]);
            int lengthOffset = markerOffset + 1;
            if (marker == 0xda || marker == 0xd9 || marker == 0xd8 || marker == 0x01
                    || (marker >= 0xd0 && marker <= 0xd7)) {
                output.write(source, markerStart, markerOffset - markerStart + 1);
                offset = markerOffset + 1;
                if (marker == 0xda) {
                    output.write(source, offset, source.length - offset);
                    break;
                }
                continue;
            }
            if (lengthOffset + 2 > source.length) {
                output.write(source, markerStart, source.length - markerStart);
                break;
            }
            int segmentLength = readU16(source, lengthOffset, false);
            int segmentEnd = lengthOffset + segmentLength;
            if (segmentLength < 2 || segmentEnd > source.length) {
                output.write(source, markerStart, source.length - markerStart);
                break;
            }
            int payloadStart = lengthOffset + 2;
            int payloadEnd = segmentEnd;
            if (marker == 0xe1 && startsWith(source, payloadStart, payloadEnd, JPEG_EXIF_PREFIX)) {
                removedExif = true;
                if (!wroteOrientation) {
                    int orientation = readTiffOrientation(source, payloadStart + 6, payloadEnd);
                    if (orientation != 1) {
                        writeJpegOrientation(output, orientation);
                    }
                    wroteOrientation = true;
                }
            } else {
                output.write(source, markerStart, segmentEnd - markerStart);
            }
            offset = segmentEnd;
        }
        return removedExif ? output.toByteArray() : source;
    }

    private static int jpegOrientation(byte[] source) {
        int offset = 2;
        while (offset + 4 <= source.length
                && unsigned(source[0]) == 0xff
                && unsigned(source[1]) == 0xd8) {
            if (unsigned(source[offset]) != 0xff) {
                return 1;
            }
            int markerOffset = offset + 1;
            while (markerOffset < source.length && unsigned(source[markerOffset]) == 0xff) {
                markerOffset++;
            }
            if (markerOffset >= source.length || unsigned(source[markerOffset]) == 0xda) {
                return 1;
            }
            int marker = unsigned(source[markerOffset]);
            int lengthOffset = markerOffset + 1;
            if (marker == 0xd9 || marker == 0xd8 || marker == 0x01
                    || (marker >= 0xd0 && marker <= 0xd7)) {
                offset = markerOffset + 1;
                continue;
            }
            if (lengthOffset + 2 > source.length) {
                return 1;
            }
            int segmentLength = readU16(source, lengthOffset, false);
            int end = lengthOffset + segmentLength;
            if (segmentLength < 2 || end > source.length) {
                return 1;
            }
            int payloadStart = lengthOffset + 2;
            if (marker == 0xe1
                    && startsWith(source, payloadStart, end, JPEG_EXIF_PREFIX)) {
                return readTiffOrientation(source, payloadStart + 6, end);
            }
            offset = end;
        }
        return 1;
    }

    private static byte[] stripPng(byte[] source) throws IOException {
        if (!startsWith(source, 0, source.length, PNG_SIGNATURE)) {
            return source;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
        output.write(PNG_SIGNATURE);
        int offset = PNG_SIGNATURE.length;
        boolean removedExif = false;
        boolean wroteOrientation = false;
        while (offset + 12 <= source.length) {
            long chunkLengthLong = readU32(source, offset, false);
            if (chunkLengthLong > Integer.MAX_VALUE) {
                throw new IOException("invalid PNG chunk length");
            }
            int chunkLength = (int) chunkLengthLong;
            int chunkEnd = offset + 12 + chunkLength;
            if (chunkEnd < offset || chunkEnd > source.length) {
                throw new IOException("truncated PNG chunk");
            }
            String type = ascii(source, offset + 4, 4);
            if ("eXIf".equals(type)) {
                removedExif = true;
                if (!wroteOrientation) {
                    int orientation = readTiffOrientation(
                            source, offset + 8, offset + 8 + chunkLength);
                    if (orientation != 1) {
                        writePngChunk(output, "eXIf", minimalTiff(orientation));
                    }
                    wroteOrientation = true;
                }
            } else {
                output.write(source, offset, chunkEnd - offset);
            }
            offset = chunkEnd;
            if ("IEND".equals(type)) {
                break;
            }
        }
        if (offset < source.length) {
            output.write(source, offset, source.length - offset);
        }
        return removedExif ? output.toByteArray() : source;
    }

    private static int pngOrientation(byte[] source) {
        if (!startsWith(source, 0, source.length, PNG_SIGNATURE)) {
            return 1;
        }
        int offset = PNG_SIGNATURE.length;
        while (offset + 12 <= source.length) {
            long length = readU32(source, offset, false);
            if (length > Integer.MAX_VALUE || offset + 12L + length > source.length) {
                return 1;
            }
            int chunkLength = (int) length;
            String type = ascii(source, offset + 4, 4);
            if ("eXIf".equals(type)) {
                return readTiffOrientation(source, offset + 8, offset + 8 + chunkLength);
            }
            offset += 12 + chunkLength;
            if ("IEND".equals(type)) {
                break;
            }
        }
        return 1;
    }

    private static byte[] stripWebp(byte[] source) throws IOException {
        if (source.length < 12 || !ascii(source, 0, 4).equals("RIFF")
                || !ascii(source, 8, 4).equals("WEBP")) {
            return source;
        }
        List<WebpChunk> chunks = new ArrayList<>();
        int offset = 12;
        boolean removedExif = false;
        int orientation = 1;
        boolean capturedOrientation = false;
        while (offset + 8 <= source.length) {
            String type = ascii(source, offset, 4);
            long lengthLong = readU32(source, offset + 4, true);
            if (lengthLong > Integer.MAX_VALUE) {
                throw new IOException("invalid WebP chunk length");
            }
            int length = (int) lengthLong;
            int dataStart = offset + 8;
            int dataEnd = dataStart + length;
            int chunkEnd = dataEnd + (length & 1);
            if (dataEnd < dataStart || chunkEnd > source.length) {
                throw new IOException("truncated WebP chunk");
            }
            byte[] data = new byte[length];
            System.arraycopy(source, dataStart, data, 0, length);
            if ("EXIF".equals(type)) {
                removedExif = true;
                if (!capturedOrientation) {
                    orientation = readTiffOrientation(data, 0, data.length);
                    capturedOrientation = true;
                    if (orientation != 1) {
                        chunks.add(new WebpChunk("EXIF", minimalExifPayload(orientation)));
                    }
                }
            } else {
                chunks.add(new WebpChunk(type, data));
            }
            offset = chunkEnd;
        }
        if (!removedExif) {
            return source;
        }
        for (WebpChunk chunk : chunks) {
            if ("VP8X".equals(chunk.type) && chunk.data.length > 0) {
                if (orientation == 1) {
                    chunk.data[0] = (byte) (chunk.data[0] & ~0x08);
                } else {
                    chunk.data[0] = (byte) (chunk.data[0] | 0x08);
                }
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
        output.write(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
        for (WebpChunk chunk : chunks) {
            writeAscii(output, chunk.type);
            writeU32(output, chunk.data.length, true);
            output.write(chunk.data);
            if ((chunk.data.length & 1) != 0) {
                output.write(0);
            }
        }
        byte[] result = output.toByteArray();
        writeU32(result, 4, result.length - 8, true);
        return result;
    }

    private static int webpOrientation(byte[] source) {
        if (source.length < 12 || !ascii(source, 0, 4).equals("RIFF")
                || !ascii(source, 8, 4).equals("WEBP")) {
            return 1;
        }
        int offset = 12;
        while (offset + 8 <= source.length) {
            String type = ascii(source, offset, 4);
            long length = readU32(source, offset + 4, true);
            if (length > Integer.MAX_VALUE || offset + 8L + length > source.length) {
                return 1;
            }
            if ("EXIF".equals(type)) {
                return readTiffOrientation(source, offset + 8, offset + 8 + (int) length);
            }
            offset += 8 + (int) length + ((int) length & 1);
        }
        return 1;
    }

    private static int readTiffOrientation(byte[] bytes, int start, int end) {
        if (start < 0 || end > bytes.length || end - start < 8) {
            return 1;
        }
        if (startsWith(bytes, start, end, JPEG_EXIF_PREFIX)) {
            start += JPEG_EXIF_PREFIX.length;
        }
        if (end - start < 8) {
            return 1;
        }
        boolean littleEndian;
        if (bytes[start] == 'I' && bytes[start + 1] == 'I') {
            littleEndian = true;
        } else if (bytes[start] == 'M' && bytes[start + 1] == 'M') {
            littleEndian = false;
        } else {
            return 1;
        }
        if (readU16(bytes, start + 2, littleEndian) != 42) {
            return 1;
        }
        long ifdOffset = readU32(bytes, start + 4, littleEndian);
        long ifdStartLong = start + ifdOffset;
        if (ifdStartLong + 2 > end) {
            return 1;
        }
        int ifdStart = (int) ifdStartLong;
        int entries = readU16(bytes, ifdStart, littleEndian);
        for (int index = 0; index < entries; index++) {
            int entry = ifdStart + 2 + index * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (readU16(bytes, entry, littleEndian) == 0x0112
                    && readU16(bytes, entry + 2, littleEndian) == 3
                    && readU32(bytes, entry + 4, littleEndian) >= 1) {
                int orientation = readU16(bytes, entry + 8, littleEndian);
                return orientation >= 1 && orientation <= 8 ? orientation : 1;
            }
        }
        return 1;
    }

    private static byte[] minimalExifPayload(int orientation) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(32);
        payload.write(JPEG_EXIF_PREFIX);
        payload.write(minimalTiff(orientation));
        return payload.toByteArray();
    }

    private static byte[] minimalTiff(int orientation) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(26);
        output.write('I');
        output.write('I');
        writeU16(output, 42, true);
        writeU32(output, 8, true);
        writeU16(output, 1, true);
        writeU16(output, 0x0112, true);
        writeU16(output, 3, true);
        writeU32(output, 1, true);
        writeU16(output, orientation, true);
        writeU16(output, 0, true);
        writeU32(output, 0, true);
        return output.toByteArray();
    }

    private static void writeJpegOrientation(ByteArrayOutputStream output, int orientation)
            throws IOException {
        byte[] payload = minimalExifPayload(orientation);
        output.write(0xff);
        output.write(0xe1);
        writeU16(output, payload.length + 2, false);
        output.write(payload);
    }

    private static void writePngChunk(ByteArrayOutputStream output, String type, byte[] data)
            throws IOException {
        writeU32(output, data.length, false);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeU32(output, crc.getValue(), false);
    }

    private static boolean startsWith(byte[] bytes, int offset, int end, byte[] prefix) {
        if (offset < 0 || end > bytes.length || end - offset < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[offset + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            return "";
        }
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static int readU16(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return unsigned(bytes[offset]) | unsigned(bytes[offset + 1]) << 8;
        }
        return unsigned(bytes[offset]) << 8 | unsigned(bytes[offset + 1]);
    }

    private static long readU32(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (long) unsigned(bytes[offset])
                    | (long) unsigned(bytes[offset + 1]) << 8
                    | (long) unsigned(bytes[offset + 2]) << 16
                    | (long) unsigned(bytes[offset + 3]) << 24;
        }
        return (long) unsigned(bytes[offset]) << 24
                | (long) unsigned(bytes[offset + 1]) << 16
                | (long) unsigned(bytes[offset + 2]) << 8
                | unsigned(bytes[offset + 3]);
    }

    private static void writeU16(ByteArrayOutputStream output, int value, boolean littleEndian)
            throws IOException {
        if (littleEndian) {
            output.write(value & 0xff);
            output.write((value >>> 8) & 0xff);
        } else {
            output.write((value >>> 8) & 0xff);
            output.write(value & 0xff);
        }
    }

    private static void writeU32(ByteArrayOutputStream output, long value, boolean littleEndian)
            throws IOException {
        if (littleEndian) {
            output.write((int) value & 0xff);
            output.write((int) (value >>> 8) & 0xff);
            output.write((int) (value >>> 16) & 0xff);
            output.write((int) (value >>> 24) & 0xff);
        } else {
            output.write((int) (value >>> 24) & 0xff);
            output.write((int) (value >>> 16) & 0xff);
            output.write((int) (value >>> 8) & 0xff);
            output.write((int) value & 0xff);
        }
    }

    private static void writeU32(byte[] output, int offset, long value, boolean littleEndian) {
        if (littleEndian) {
            output[offset] = (byte) value;
            output[offset + 1] = (byte) (value >>> 8);
            output[offset + 2] = (byte) (value >>> 16);
            output[offset + 3] = (byte) (value >>> 24);
        } else {
            output[offset] = (byte) (value >>> 24);
            output[offset + 1] = (byte) (value >>> 16);
            output[offset + 2] = (byte) (value >>> 8);
            output[offset + 3] = (byte) value;
        }
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static final class WebpChunk {
        private final String type;
        private final byte[] data;

        private WebpChunk(String type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }
}

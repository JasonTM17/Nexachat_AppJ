package com.nexachat.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicBytesTest {

    @Test
    void detectsJpeg() {
        byte[] header = hex("FFD8FFE000104A4649460001");
        assertEquals("image/jpeg", MagicBytes.sniff(header));
        assertTrue(MagicBytes.isAllowed(header));
    }

    @Test
    void detectsPng() {
        byte[] header = hex("89504E470D0A1A0A0000000D49484452");
        assertEquals("image/png", MagicBytes.sniff(header));
        assertTrue(MagicBytes.isAllowed(header));
    }

    @Test
    void detectsGif() {
        byte[] header = hex("47494638396101000100");
        assertEquals("image/gif", MagicBytes.sniff(header));
    }

    @Test
    void detectsWebp() {
        byte[] header = hex("524946461A0000005745425056503820");
        assertEquals("image/webp", MagicBytes.sniff(header));
    }

    @Test
    void doesNotMistakeRiffWavForWebp() {
        byte[] wav = hex("524946461A00000057415645666D7420");
        assertNull(MagicBytes.sniff(wav));
        assertFalse(MagicBytes.isAllowed(wav));
    }

    @Test
    void detectsMp4() {
        byte[] mp4 = hex("00000018667479706D703432");
        assertEquals("video/mp4", MagicBytes.sniff(mp4));
        assertTrue(MagicBytes.isAllowed(mp4));
    }

    @Test
    void detectsM4a() {
        byte[] m4a = hex("00000018667479704D344120");
        assertEquals("audio/mp4", MagicBytes.sniff(m4a));
        assertTrue(MagicBytes.isAllowed(m4a));
    }

    @Test
    void detectsOgg() {
        byte[] ogg = hex("4F67675300020000");
        assertEquals("audio/ogg", MagicBytes.sniff(ogg));
    }

    @Test
    void detectsPdf() {
        byte[] pdf = hex("255044462D312E370A25");
        assertEquals("application/pdf", MagicBytes.sniff(pdf));
    }

    @Test
    void detectsMp3Id3Tag() {
        byte[] mp3 = hex("49443303000000000000");
        assertEquals("audio/mpeg", MagicBytes.sniff(mp3));
        assertTrue(MagicBytes.isAllowed(mp3));
    }

    @Test
    void detectsMp3FrameSync() {
        byte[] mp3 = hex("FFFB900000000000");
        assertEquals("audio/mpeg", MagicBytes.sniff(mp3));
    }

    @Test
    void detectsZipContainer() {
        byte[] zip = hex("504B0304140006000800");
        assertEquals("application/zip", MagicBytes.sniff(zip));
        assertTrue(MagicBytes.isAllowed(zip));
    }

    @Test
    void detectsLegacyMsWord() {
        byte[] msword = hex("D0CF11E0A1B11AE100000000");
        assertEquals("application/msword", MagicBytes.sniff(msword));
        assertTrue(MagicBytes.isAllowed(msword));
    }

    @Test
    void rejectsUnknownBytes() {
        byte[] unknown = hex("00010203040506070809");
        assertNull(MagicBytes.sniff(unknown));
        assertFalse(MagicBytes.isAllowed(unknown));
    }

    @Test
    void rejectsExecutable() {
        byte[] exe = hex("4D5A90000300000004000000");
        assertNull(MagicBytes.sniff(exe));
        assertFalse(MagicBytes.isAllowed(exe));
    }

    @Test
    void handlesNullAndEmptyHeaders() {
        assertNull(MagicBytes.sniff((byte[]) null));
        assertNull(MagicBytes.sniff(new byte[0]));
        assertFalse(MagicBytes.isAllowed(null));
    }

    @Test
    void sniffsFromInputStream() throws IOException {
        byte[] png = hex("89504E470D0A1A0A0000000D49484452");
        try (InputStream in = new ByteArrayInputStream(png)) {
            assertEquals("image/png", MagicBytes.sniff(in));
        }
    }

    @Test
    void exposesWhitelistAndSignatures() {
        assertTrue(MagicBytes.ALLOWED_MIMES.contains("image/jpeg"));
        assertTrue(MagicBytes.ALLOWED_MIMES.contains(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertTrue(MagicBytes.ALLOWED_MIMES.contains("application/msword"));
        assertEquals("image/png", MagicBytes.SIGNATURES.get("89504E47"));
        assertEquals("application/pdf", MagicBytes.SIGNATURES.get("25504446"));
    }

    private static byte[] hex(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

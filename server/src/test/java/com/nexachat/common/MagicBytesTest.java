package com.nexachat.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

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
    void detectsM4aInCompatibleBrands() {
        byte[] m4aCompatible = hex("000000206674797069736F6D000002004D3441206D703432");
        assertEquals("audio/mp4", MagicBytes.sniff(m4aCompatible));
        assertTrue(MagicBytes.isAllowed(m4aCompatible));
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

    // ------------------------------------------- ISO-BMFF track classification

    @Test
    void classifiesAacFixtureWithMoovAtEndAsAudioMp4() throws IOException {
        byte[] file = resource("voice_aac_isom_moov_end.m4a");
        assertEquals("audio/mp4", MagicBytes.sniff(file));
        assertTrue(MagicBytes.isAllowed(file));
    }

    @Test
    void classifiesAacFixtureWithMoovAtFrontAsAudioMp4() throws IOException {
        byte[] file = resource("voice_aac_isom_faststart.m4a");
        assertEquals("audio/mp4", MagicBytes.sniff(file));
    }

    @Test
    void classifiesH264AacVideoFixtureAsVideoMp4() throws IOException {
        byte[] file = resource("video_h264_aac_isom.mp4");
        assertEquals("video/mp4", MagicBytes.sniff(file));
        assertTrue(MagicBytes.isAllowed(file));
    }

    @Test
    void classifiesAacFromPrefixAndSuffixWindows() throws IOException {
        byte[] file = resource("voice_aac_isom_moov_end.m4a");
        byte[] prefix = Arrays.copyOfRange(file, 0, 32);
        // The fixture's moov is ~942 bytes, so the trailing window must cover it.
        byte[] suffix = Arrays.copyOfRange(file, file.length - 2048, file.length);
        assertTrue(MagicBytes.isIsoBmffUndetermined(prefix));
        assertEquals("audio/mp4", MagicBytes.sniff(prefix, suffix));
    }

    @Test
    void classifiesLargeMoovAtEndFileFromPrefixAndSuffix() {
        byte[] file = concat(ftyp("isom", "isom", "iso2"),
                box("mdat", new byte[200_000]),
                moov("soun"));
        byte[] prefix = Arrays.copyOfRange(file, 0, 64 * 1024);
        byte[] suffix = Arrays.copyOfRange(file, file.length - 64 * 1024, file.length);
        assertEquals("audio/mp4", MagicBytes.sniff(prefix, suffix));
    }

    @Test
    void genericBrandWithOnlySounTrackIsAudio() {
        byte[] file = concat(ftyp("isom", "isom", "iso2"), moov("soun"));
        assertEquals("audio/mp4", MagicBytes.sniff(file));
    }

    @Test
    void genericBrandWithVideAndSounTracksIsVideo() {
        byte[] file = concat(ftyp("isom", "isom", "iso2"), moov("vide", "soun"));
        assertEquals("video/mp4", MagicBytes.sniff(file));
    }

    @Test
    void genericBrandWithOnlyVideTrackIsVideo() {
        byte[] file = concat(ftyp("isom", "isom", "iso2"), moov("vide"));
        assertEquals("video/mp4", MagicBytes.sniff(file));
    }

    @Test
    void genericBrandWithoutMoovKeepsBrandResult() {
        byte[] file = ftyp("isom", "isom", "iso2");
        assertEquals("video/mp4", MagicBytes.sniff(file));
    }

    @Test
    void truncatedMoovFallsBackToBrand() {
        byte[] moovHeader = box("moov", new byte[0]);
        // A size that extends past the file makes the box unusable.
        moovHeader[0] = 0x7F;
        moovHeader[1] = (byte) 0xFF;
        moovHeader[2] = (byte) 0xFF;
        moovHeader[3] = (byte) 0xFF;
        byte[] file = concat(ftyp("isom", "isom", "iso2"), moovHeader);
        assertEquals("video/mp4", MagicBytes.sniff(file));
    }

    @Test
    void moovWithoutTracksFallsBackToBrand() {
        byte[] file = concat(ftyp("isom", "isom", "iso2"), box("moov", new byte[0]));
        assertEquals("video/mp4", MagicBytes.sniff(file));
    }

    @Test
    void malformedTrakFallsBackToBrand() {
        byte[] badTrak = box("trak", new byte[0]);
        badTrak[0] = 0x7F;
        badTrak[1] = (byte) 0xFF;
        badTrak[2] = (byte) 0xFF;
        badTrak[3] = (byte) 0xFF;
        byte[] file = concat(ftyp("isom", "isom", "iso2"), box("moov", badTrak));
        assertEquals("video/mp4", MagicBytes.sniff(file));
    }

    @Test
    void zeroSizeBoxTerminatesWithoutLooping() {
        byte[] file = concat(ftyp("isom", "isom", "iso2"),
                new byte[] {0, 0, 0, 0, 'm', 'd', 'a', 't'});
        assertEquals("video/mp4", MagicBytes.sniff(file));
    }

    @Test
    void sixtyFourBitSizeBoxIsHandled() {
        byte[] file = concat(ftyp("isom", "isom", "iso2"),
                new byte[] {0, 0, 0, 1, 'm', 'd', 'a', 't',
                        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEquals("video/mp4", MagicBytes.sniff(file));
    }

    @Test
    void fuzzHostileFtypFilesNeverThrowAndStayWhitelisted() {
        Random random = new Random(42);
        for (int i = 0; i < 5000; i++) {
            byte[] data = new byte[random.nextInt(512)];
            random.nextBytes(data);
            if (data.length >= 12) {
                data[4] = 'f';
                data[5] = 't';
                data[6] = 'y';
                data[7] = 'p';
            }
            String mime = MagicBytes.sniff(data);
            if (mime != null) {
                assertTrue(MagicBytes.ALLOWED_MIMES.contains(mime), "non-whitelisted " + mime);
            }
        }
    }

    @Test
    void largeHostileFtypFileIsBounded() {
        byte[] data = new byte[1024 * 1024];
        new Random(7).nextBytes(data);
        data[4] = 'f';
        data[5] = 't';
        data[6] = 'y';
        data[7] = 'p';
        long start = System.nanoTime();
        String mime = MagicBytes.sniff(data);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (mime != null) {
            assertTrue(MagicBytes.ALLOWED_MIMES.contains(mime));
        }
        assertTrue(elapsedMs < 1000, "sniff took " + elapsedMs + " ms");
    }

    @Test
    void adversarialSuffixFullOfMoovMarkersIsBounded() {
        byte[] filler = new byte[200_000];
        for (int i = 0; i + 4 <= filler.length; i += 4) {
            filler[i] = 'm';
            filler[i + 1] = 'o';
            filler[i + 2] = 'o';
            filler[i + 3] = 'v';
        }
        byte[] file = concat(ftyp("isom", "isom", "iso2"), box("mdat", filler), moov("soun"));
        byte[] prefix = Arrays.copyOfRange(file, 0, 64 * 1024);
        byte[] suffix = Arrays.copyOfRange(file, file.length - 64 * 1024, file.length);
        long start = System.nanoTime();
        String mime = MagicBytes.sniff(prefix, suffix);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals("audio/mp4", mime);
        assertTrue(elapsedMs < 1000, "sniff took " + elapsedMs + " ms");
    }

    @Test
    void undeterminedOnlyForGenericBrandWithoutTracks() throws IOException {
        byte[] aac = resource("voice_aac_isom_moov_end.m4a");
        assertTrue(MagicBytes.isIsoBmffUndetermined(Arrays.copyOf(aac, 32)));
        assertFalse(MagicBytes.isIsoBmffUndetermined(aac));
        assertFalse(MagicBytes.isIsoBmffUndetermined(resource("video_h264_aac_isom.mp4")));
        assertFalse(MagicBytes.isIsoBmffUndetermined(hex("89504E470D0A1A0A")));
        assertFalse(MagicBytes.isIsoBmffUndetermined(null));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = MagicBytesTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture: " + name);
            }
            return in.readAllBytes();
        }
    }

    private static byte[] box(String type, byte[] payload) {
        int size = 8 + payload.length;
        byte[] out = new byte[size];
        out[0] = (byte) (size >>> 24);
        out[1] = (byte) (size >>> 16);
        out[2] = (byte) (size >>> 8);
        out[3] = (byte) size;
        for (int i = 0; i < 4; i++) {
            out[4 + i] = (byte) type.charAt(i);
        }
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] ftyp(String major, String... compatible) {
        byte[] payload = new byte[8 + 4 * compatible.length];
        System.arraycopy(major.getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 4);
        for (int i = 0; i < compatible.length; i++) {
            System.arraycopy(compatible[i].getBytes(StandardCharsets.US_ASCII), 0,
                    payload, 8 + 4 * i, 4);
        }
        return box("ftyp", payload);
    }

    private static byte[] moov(String... handlers) {
        byte[] payload = new byte[0];
        for (String handler : handlers) {
            payload = concat(payload, trak(handler));
        }
        return box("moov", payload);
    }

    private static byte[] trak(String handler) {
        byte[] hdlrPayload = new byte[16];
        System.arraycopy(handler.getBytes(StandardCharsets.US_ASCII), 0, hdlrPayload, 8, 4);
        return box("trak", box("mdia", box("hdlr", hdlrPayload)));
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

package com.nexachat.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sniffs a MIME type from the leading magic bytes of an uploaded file.
 *
 * <p><strong>Never trust the {@code Content-Type} supplied by the client.</strong>
 * A client can label an executable or HTML page as {@code image/png}; only the
 * actual bytes can establish what a file really is. Always sniff server-side
 * before persisting, serving, or rendering an upload:</p>
 *
 * <pre>{@code
 * try (InputStream in = file.getInputStream()) {
 *     if (!MagicBytes.isAllowed(MagicBytes.sniff(in))) {
 *         throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
 *     }
 * }
 * }</pre>
 *
 * <p>Detection is conservative: unknown or non-whitelisted content yields
 * {@code null} from {@link #sniff(byte[])} and {@code false} from
 * {@link #isAllowed(byte[])}. A ZIP container is reported as
 * {@code application/zip}; distinguishing DOCX/XLSX requires opening the
 * archive (for example, looking for {@code word/document.xml}) and is left to
 * the caller.</p>
 */
public final class MagicBytes {

    /** Prefix shared by OOXML documents (DOCX, XLSX, PPTX, ...). */
    private static final String OOXML_PREFIX =
            "application/vnd.openxmlformats-officedocument.";

    /** Mime types accepted for upload. */
    public static final Set<String> ALLOWED_MIMES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "video/mp4",
            "audio/mpeg",
            "audio/mp4",
            "audio/ogg",
            "application/pdf",
            "text/plain",
            "text/csv",
            "text/markdown",
            "application/zip",
            "application/msword",
            OOXML_PREFIX,
            OOXML_PREFIX + "wordprocessingml.document",
            OOXML_PREFIX + "spreadsheetml.sheet",
            OOXML_PREFIX + "presentationml.presentation"
    );

    /** Number of leading bytes read from a stream. */
    private static final int HEADER_LENGTH = 32;

    private static final String IMAGE_JPEG = "image/jpeg";
    private static final String IMAGE_PNG = "image/png";
    private static final String IMAGE_WEBP = "image/webp";
    private static final String IMAGE_GIF = "image/gif";
    private static final String VIDEO_MP4 = "video/mp4";
    private static final String AUDIO_MPEG = "audio/mpeg";
    private static final String AUDIO_MP4 = "audio/mp4";
    private static final String AUDIO_OGG = "audio/ogg";
    private static final String APPLICATION_PDF = "application/pdf";
    private static final String APPLICATION_ZIP = "application/zip";
    private static final String APPLICATION_MSWORD = "application/msword";

    /** Fixed-prefix signatures that can be matched directly at offset 0. */
    private static final Map<String, String> EXACT_SIGNATURES = Map.ofEntries(
            Map.entry("FFD8FF", IMAGE_JPEG),
            Map.entry("89504E47", IMAGE_PNG),
            Map.entry("47494638", IMAGE_GIF),
            Map.entry("4F676753", AUDIO_OGG),
            Map.entry("25504446", APPLICATION_PDF),
            Map.entry("504B0304", APPLICATION_ZIP),
            Map.entry("D0CF11E0", APPLICATION_MSWORD)
    );

    /**
     * Well-known magic-byte signatures, keyed by uppercase hex prefix.
     *
     * <p>Entries whose format requires looking past the prefix (RIFF/WEBP,
     * ISO-BMFF {@code ftyp}, MPEG audio frames) are reference documentation:
     * {@link #sniff(byte[])} validates those fully before falling back to the
     * fixed prefixes. Hex keys are compared case-insensitively.</p>
     */
    public static final Map<String, String> SIGNATURES = buildSignatures();

    private MagicBytes() {
        // Utility class: no instances.
    }

    private static Map<String, String> buildSignatures() {
        Map<String, String> signatures = new LinkedHashMap<>(EXACT_SIGNATURES);
        signatures.put("52494646", IMAGE_WEBP); // RIFF....WEBP (WEBP checked at offset 8)
        signatures.put("66747970", VIDEO_MP4);  // ftyp box (major brand checked at offset 8)
        signatures.put("494433", AUDIO_MPEG);   // ID3v2 tag
        signatures.put("FF", AUDIO_MPEG);       // MPEG audio frame sync (FFFx)
        return Collections.unmodifiableMap(signatures);
    }

    /**
     * Sniffs a MIME type from the leading bytes of {@code header}.
     *
     * @param header leading bytes of the file; {@code null} or empty is allowed
     * @return the detected MIME type, or {@code null} when the signature is
     *         unknown or too short to classify
     */
    public static String sniff(byte[] header) {
        if (header == null || header.length == 0) {
            return null;
        }
        // MP3: ID3v2 tag (49 44 33) or frame sync (FF Ex / FF Fx).
        if (matchesHex(header, 0, "494433")) {
            return AUDIO_MPEG;
        }
        if (header.length >= 2
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xE0) == 0xE0) {
            return AUDIO_MPEG;
        }
        // WebP: RIFF....WEBP.
        if (header.length >= 12
                && matchesHex(header, 0, "52494646")
                && matchesAscii(header, 8, "WEBP")) {
            return IMAGE_WEBP;
        }
        // ISO base media: ftyp box; major brand at offset 8 selects audio vs video.
        if (header.length >= 12 && matchesAscii(header, 4, "ftyp")) {
            return ftypMime(header);
        }
        // Fixed-prefix signatures.
        for (Map.Entry<String, String> entry : EXACT_SIGNATURES.entrySet()) {
            if (matchesHex(header, 0, entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Reads up to {@value #HEADER_LENGTH} leading bytes from {@code in} and
     * sniffs the MIME type.
     *
     * <p>The stream is not closed by this method; the caller owns it.</p>
     *
     * @param in stream positioned at the start of the file; may be {@code null}
     * @return the detected MIME type, or {@code null} when it cannot be determined
     * @throws UncheckedIOException if the stream cannot be read
     */
    public static String sniff(InputStream in) {
        if (in == null) {
            return null;
        }
        try {
            return sniff(in.readNBytes(HEADER_LENGTH));
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read file header for MIME sniffing", ex);
        }
    }

    /**
     * Checks whether the sniffed type of {@code header} is in
     * {@link #ALLOWED_MIMES}.
     *
     * @param header leading bytes of the file; may be {@code null}
     * @return {@code false} when the type is unknown or not whitelisted
     */
    public static boolean isAllowed(byte[] header) {
        String mime = sniff(header);
        if (mime == null) {
            return false;
        }
        if (ALLOWED_MIMES.contains(mime)) {
            return true;
        }
        // Wildcard-style entries such as
        // "application/vnd.openxmlformats-officedocument.*" are stored as prefixes.
        for (String allowed : ALLOWED_MIMES) {
            if (allowed.endsWith(".") && mime.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static String ftypMime(byte[] header) {
        // Caller guarantees at least 12 bytes, so the 4-byte brand at offset 8 exists.
        String brand = new String(header, 8, 4, StandardCharsets.US_ASCII)
                .trim()
                .toLowerCase(Locale.ROOT);
        if ("m4a".equals(brand)) {
            return AUDIO_MP4;
        }
        if (header.length >= 16) {
            String ftypHeader = new String(header, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (ftypHeader.contains("m4a")) {
                return AUDIO_MP4;
            }
        }
        switch (brand) {
            case "mp41":
            case "mp42":
            case "isom":
                return VIDEO_MP4;
            default:
                return null;
        }
    }

    private static boolean matchesHex(byte[] header, int offset, String hexPrefix) {
        int length = hexPrefix.length() / 2;
        if (offset < 0 || header.length - offset < length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            int expected = (Character.digit(hexPrefix.charAt(i * 2), 16) << 4)
                    | Character.digit(hexPrefix.charAt(i * 2 + 1), 16);
            if ((header[offset + i] & 0xFF) != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAscii(byte[] header, int offset, String ascii) {
        if (offset < 0 || header.length - offset < ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if ((header[offset + i] & 0xFF) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}

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
 *
 * <p><strong>ISO-BMFF (MP4/M4A).</strong> The {@code ftyp} major brand alone
 * cannot tell an audio-only recording from a video: Android's
 * {@code MediaRecorder} writes a generic brand ({@code isom}/{@code mp42}) for
 * {@code .m4a} voice notes, so a brand-only check reports {@code video/mp4}.
 * When the brand is generic, the box tree is walked and each
 * {@code trak/mdia/hdlr} handler is read: only {@code soun} tracks classify as
 * {@code audio/mp4}, any {@code vide} track as {@code video/mp4}. A
 * {@code moov} box that is missing, truncated, or malformed leaves the
 * brand-only result in place (fail-closed: an unverifiable generic file is
 * treated as video, never as audio). The walk is bounded by the number of
 * boxes, never allocates per box, and rejects zero-size, oversized, and
 * 64-bit size headers without looping or throwing.</p>
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

    /**
     * Upper bound on boxes inspected per container, so a hostile file made of
     * millions of tiny boxes cannot turn sniffing into unbounded work.
     */
    private static final int MAX_BOXES = 8192;

    /** Upper bound on tracks inspected per {@code moov} box. */
    private static final int MAX_TRACKS = 256;

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

    /** Result of looking for track handlers inside an ISO-BMFF box tree. */
    private enum TrackKind {
        /** No {@code moov} box was found in the bytes available. */
        NONE,
        /** A {@code moov} box exists but could not be parsed completely. */
        AMBIGUOUS,
        /** A complete {@code moov} with at least one {@code soun} track and no {@code vide} track. */
        AUDIO,
        /** A complete {@code moov} with at least one {@code vide} track. */
        VIDEO
    }

    private static final int TRACK_NONE = 0;
    private static final int TRACK_AUDIO = 1;
    private static final int TRACK_VIDEO = 2;
    private static final int TRACK_MALFORMED = -1;

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
        return sniff(header, null);
    }

    /**
     * Sniffs a MIME type from a leading window and an optional trailing window
     * of the same file.
     *
     * <p>The trailing window exists for ISO-BMFF files whose {@code moov} box
     * sits at the end and is therefore absent from the leading window. It is
     * only consulted when the leading window starts with an {@code ftyp} box
     * whose kind cannot be decided from the brand or the boxes present.</p>
     *
     * @param prefix leading bytes of the file; {@code null} or empty is allowed
     * @param suffix trailing bytes of the file, or {@code null} when unavailable
     * @return the detected MIME type, or {@code null} when the signature is
     *         unknown or too short to classify
     */
    public static String sniff(byte[] prefix, byte[] suffix) {
        if (prefix == null || prefix.length == 0) {
            return null;
        }
        // MP3: ID3v2 tag (49 44 33) or frame sync (FF Ex / FF Fx).
        if (matchesHex(prefix, 0, "494433")) {
            return AUDIO_MPEG;
        }
        if (prefix.length >= 2
                && (prefix[0] & 0xFF) == 0xFF
                && (prefix[1] & 0xE0) == 0xE0) {
            return AUDIO_MPEG;
        }
        // WebP: RIFF....WEBP.
        if (prefix.length >= 12
                && matchesHex(prefix, 0, "52494646")
                && matchesAscii(prefix, 8, "WEBP")) {
            return IMAGE_WEBP;
        }
        // ISO base media: ftyp box; tracks select audio vs video.
        if (prefix.length >= 12 && matchesAscii(prefix, 4, "ftyp")) {
            return isoBmffMime(prefix, suffix);
        }
        // Fixed-prefix signatures.
        for (Map.Entry<String, String> entry : EXACT_SIGNATURES.entrySet()) {
            if (matchesHex(prefix, 0, entry.getKey())) {
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

    /**
     * Reports whether {@code prefix} starts with an ISO-BMFF {@code ftyp} box
     * whose audio/video kind is not yet decided.
     *
     * <p>Callers that serve a file from a stream can use this to decide whether
     * reading a trailing window is worthwhile. A definitive brand ({@code m4a}
     * or a compatible {@code m4a} brand) or a complete {@code moov} box in the
     * prefix means no trailing read is needed.</p>
     *
     * @param prefix leading bytes of the file; may be {@code null}
     * @return {@code true} when a trailing window may change the classification
     */
    public static boolean isIsoBmffUndetermined(byte[] prefix) {
        if (prefix == null || prefix.length < 12 || !matchesAscii(prefix, 4, "ftyp")) {
            return false;
        }
        if ("m4a".equals(majorBrand(prefix)) || hasCompatibleBrand(prefix, "m4a")) {
            return false;
        }
        TrackKind kind = scanTracks(prefix, 0, prefix.length);
        return kind == TrackKind.NONE || kind == TrackKind.AMBIGUOUS;
    }

    private static String isoBmffMime(byte[] prefix, byte[] suffix) {
        TrackKind kind = scanTracks(prefix, 0, prefix.length);
        if ((kind == TrackKind.NONE || kind == TrackKind.AMBIGUOUS)
                && suffix != null && suffix.length >= 8) {
            TrackKind tailKind = scanTracksInSuffix(suffix);
            if (tailKind == TrackKind.AUDIO || tailKind == TrackKind.VIDEO) {
                kind = tailKind;
            }
        }
        if (kind == TrackKind.AUDIO) {
            return AUDIO_MP4;
        }
        if (kind == TrackKind.VIDEO) {
            return VIDEO_MP4;
        }
        // No usable track information: keep the historical brand-only result.
        String brand = majorBrand(prefix);
        if ("m4a".equals(brand) || hasCompatibleBrand(prefix, "m4a")) {
            return AUDIO_MP4;
        }
        return switch (brand) {
            case "mp41", "mp42", "isom" -> VIDEO_MP4;
            default -> null;
        };
    }

    private static String majorBrand(byte[] data) {
        // Caller guarantees at least 12 bytes, so the 4-byte brand at offset 8 exists.
        return ascii(data, 8, 4).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasCompatibleBrand(byte[] data, String brand) {
        if (data.length < 16) {
            return false;
        }
        long boxSize = resolveBoxSize(data, 0, data.length);
        if (boxSize < 0) {
            // Truncated ftyp box: inspect only the brands that are present,
            // bounded so a huge file cannot turn this into a full scan.
            boxSize = Math.min(data.length, 4096);
        }
        for (int i = 16; i + 4 <= boxSize; i += 4) {
            if (brand.equals(ascii(data, i, 4).trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the top-level boxes of {@code [start, end)} looking for a complete
     * {@code moov} box and classifies its tracks.
     */
    private static TrackKind scanTracks(byte[] data, int start, int end) {
        int pos = start;
        int boxes = 0;
        boolean moovFound = false;
        while (pos + 8 <= end && boxes < MAX_BOXES) {
            boxes++;
            long size = resolveBoxSize(data, pos, end);
            if (size < 0) {
                return moovFound ? TrackKind.AMBIGUOUS : TrackKind.NONE;
            }
            int header = boxHeaderSize(data, pos, end);
            int boxEnd = pos + (int) size;
            if (matchesAscii(data, pos + 4, "moov")) {
                moovFound = true;
                TrackKind kind = scanMoov(data, pos + header, boxEnd);
                if (kind == TrackKind.AUDIO || kind == TrackKind.VIDEO) {
                    return kind;
                }
                return TrackKind.AMBIGUOUS;
            }
            pos = boxEnd;
        }
        return moovFound || boxes >= MAX_BOXES ? TrackKind.AMBIGUOUS : TrackKind.NONE;
    }

    /**
     * Scans a trailing window that may start mid-box for a {@code moov} box
     * header, then classifies its tracks. Only a {@code moov} whose declared
     * size fits entirely inside the window is considered.
     */
    private static TrackKind scanTracksInSuffix(byte[] suffix) {
        // One linear pass: a false "moov" marker costs one size check, and a
        // real moov is bounded by scanMoov's own box cap.
        int limit = suffix.length - 8;
        for (int boxStart = 0; boxStart <= limit; boxStart++) {
            if (!matchesAscii(suffix, boxStart + 4, "moov")) {
                continue;
            }
            long size = resolveBoxSize(suffix, boxStart, suffix.length);
            if (size < 0) {
                continue;
            }
            int header = boxHeaderSize(suffix, boxStart, suffix.length);
            int boxEnd = boxStart + (int) size;
            TrackKind kind = scanMoov(suffix, boxStart + header, boxEnd);
            if (kind == TrackKind.AUDIO || kind == TrackKind.VIDEO) {
                return kind;
            }
        }
        return TrackKind.NONE;
    }

    /** Classifies the tracks of one {@code moov} box spanning {@code [start, end)}. */
    private static TrackKind scanMoov(byte[] data, int start, int end) {
        boolean hasAudio = false;
        int tracks = 0;
        int pos = start;
        int boxes = 0;
        while (pos + 8 <= end && boxes < MAX_BOXES) {
            boxes++;
            long size = resolveBoxSize(data, pos, end);
            if (size < 0) {
                return TrackKind.AMBIGUOUS;
            }
            int header = boxHeaderSize(data, pos, end);
            int boxEnd = pos + (int) size;
            if (matchesAscii(data, pos + 4, "trak")) {
                if (tracks >= MAX_TRACKS) {
                    return TrackKind.AMBIGUOUS;
                }
                tracks++;
                int track = scanTrak(data, pos + header, boxEnd);
                if (track == TRACK_MALFORMED) {
                    return TrackKind.AMBIGUOUS;
                }
                if (track == TRACK_VIDEO) {
                    return TrackKind.VIDEO;
                }
                if (track == TRACK_AUDIO) {
                    hasAudio = true;
                }
            }
            pos = boxEnd;
        }
        if (boxes >= MAX_BOXES) {
            return TrackKind.AMBIGUOUS;
        }
        return hasAudio ? TrackKind.AUDIO : TrackKind.AMBIGUOUS;
    }

    /** Reads the handler of one {@code trak} box spanning {@code [start, end)}. */
    private static int scanTrak(byte[] data, int start, int end) {
        int pos = start;
        int boxes = 0;
        while (pos + 8 <= end && boxes < MAX_BOXES) {
            boxes++;
            long size = resolveBoxSize(data, pos, end);
            if (size < 0) {
                return TRACK_MALFORMED;
            }
            int header = boxHeaderSize(data, pos, end);
            int boxEnd = pos + (int) size;
            if (matchesAscii(data, pos + 4, "mdia")) {
                return scanMdia(data, pos + header, boxEnd);
            }
            pos = boxEnd;
        }
        return boxes >= MAX_BOXES ? TRACK_MALFORMED : TRACK_NONE;
    }

    /** Reads the handler of one {@code mdia} box spanning {@code [start, end)}. */
    private static int scanMdia(byte[] data, int start, int end) {
        int pos = start;
        int boxes = 0;
        while (pos + 8 <= end && boxes < MAX_BOXES) {
            boxes++;
            long size = resolveBoxSize(data, pos, end);
            if (size < 0) {
                return TRACK_MALFORMED;
            }
            int header = boxHeaderSize(data, pos, end);
            int boxEnd = pos + (int) size;
            if (matchesAscii(data, pos + 4, "hdlr")) {
                return handlerKind(data, pos, boxEnd);
            }
            pos = boxEnd;
        }
        return boxes >= MAX_BOXES ? TRACK_MALFORMED : TRACK_NONE;
    }

    /**
     * Reads the handler type of an {@code hdlr} box. The handler sits at offset
     * 16: size (4) + type (4) + version/flags (4) + pre_defined (4).
     */
    private static int handlerKind(byte[] data, int boxStart, int boxEnd) {
        int handlerOffset = boxStart + 16;
        if (handlerOffset + 4 > boxEnd) {
            return TRACK_MALFORMED;
        }
        if (matchesAscii(data, handlerOffset, "vide")) {
            return TRACK_VIDEO;
        }
        if (matchesAscii(data, handlerOffset, "soun")) {
            return TRACK_AUDIO;
        }
        return TRACK_NONE;
    }

    /**
     * Resolves the size of the box starting at {@code pos} inside
     * {@code [start, end)}.
     *
     * @return the box size in bytes, or {@code -1} when the header is malformed
     *         or the box extends past {@code end}
     */
    private static long resolveBoxSize(byte[] data, int pos, int end) {
        if (pos + 8 > end) {
            return -1;
        }
        long size = readUnsignedInt(data, pos);
        if (size == 1) {
            if (pos + 16 > end) {
                return -1;
            }
            size = readUnsignedLong(data, pos + 8);
            if (size < 16) {
                return -1;
            }
        } else if (size == 0) {
            // A zero size means the box extends to the end of its container.
            size = (long) end - pos;
        } else if (size < 8) {
            return -1;
        }
        if (size > (long) end - pos) {
            return -1;
        }
        return size;
    }

    private static int boxHeaderSize(byte[] data, int pos, int end) {
        if (pos + 8 > end) {
            return -1;
        }
        return readUnsignedInt(data, pos) == 1 && pos + 16 <= end ? 16 : 8;
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static long readUnsignedLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
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

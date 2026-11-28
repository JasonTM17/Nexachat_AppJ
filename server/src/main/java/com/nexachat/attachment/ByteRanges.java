package com.nexachat.attachment;

/**
 * HTTP {@code Range} header parsing for byte-range media endpoints.
 *
 * <p>Implements single-byte-range parsing per RFC 9110 ({@code bytes=start-end},
 * {@code bytes=start-}, {@code bytes=-suffix}); only the first range of a
 * multi-range header is honoured. Malformed or unsatisfiable ranges throw
 * {@link RangeNotSatisfiableException} carrying the representation size so the
 * exception advice emits {@code 416} with {@code Content-Range: bytes &#42;/size}.
 */
public final class ByteRanges {

    public static final String BYTES_UNIT = "bytes";

    private ByteRanges() {
        // Utility class.
    }

    /** Inclusive byte range {@code [start, end]} within a representation. */
    public record ByteRange(long start, long end) {

        public long count() {
            return end - start + 1;
        }
    }

    /**
     * Parses a {@code Range} header against a representation of {@code size} bytes.
     *
     * @param header raw {@code Range} header value
     * @param size   total representation length in bytes
     * @return inclusive byte range
     * @throws RangeNotSatisfiableException when the header is malformed or unsatisfiable
     */
    public static ByteRange parse(String header, long size) {
        try {
            return parseInternal(header, size);
        } catch (IllegalArgumentException ex) {
            throw new RangeNotSatisfiableException(size, "Requested range not satisfiable");
        }
    }

    private static ByteRange parseInternal(String header, long size) {
        if (header == null) {
            throw new IllegalArgumentException("Missing range");
        }
        String spec = header.trim();
        if (!spec.regionMatches(true, 0, "bytes=", 0, "bytes=".length())) {
            throw new IllegalArgumentException("Unsupported range unit");
        }
        spec = spec.substring("bytes=".length()).trim();
        // Only the first range is honoured; RFC 9110 lets a server ignore the rest.
        int comma = spec.indexOf(',');
        if (comma >= 0) {
            spec = spec.substring(0, comma).trim();
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new IllegalArgumentException("Malformed range");
        }
        String left = spec.substring(0, dash).trim();
        String right = spec.substring(dash + 1).trim();
        if (left.isEmpty() && right.isEmpty()) {
            throw new IllegalArgumentException("Malformed range");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Empty representation");
        }

        long start;
        long end;
        if (left.isEmpty()) {
            // Suffix range: the last N bytes. N=0 is unsatisfiable.
            long suffix = parseLong(right);
            if (suffix <= 0) {
                throw new IllegalArgumentException("Empty suffix range");
            }
            start = Math.max(0, size - suffix);
            end = size - 1;
        } else {
            start = parseLong(left);
            if (start < 0 || start >= size) {
                throw new IllegalArgumentException("Range starts past the end of the representation");
            }
            if (right.isEmpty()) {
                end = size - 1;
            } else {
                end = parseLong(right);
                if (end < start) {
                    throw new IllegalArgumentException("Range ends before it starts");
                }
                if (end >= size) {
                    end = size - 1;
                }
            }
        }
        return new ByteRange(start, end);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed range number", ex);
        }
    }
}

package com.nexachat.attachment;

import com.nexachat.common.MagicBytes;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.UUID;

/**
 * Streams stored files through the API after the existing authorization checks.
 *
 * <p>The storage key is the request path after {@code /api/v1/files/}. It is
 * normalized and validated before any read, so a crafted key cannot address
 * a different object. The response Content-Type
 * is re-sniffed from the stored bytes — the same sniff the store path applied
 * — with {@code application/octet-stream} as the final fallback.</p>
 *
 * <p><b>ISO-BMFF window:</b> a brand-only sniff cannot tell an audio-only
 * voice note from a video because Android's {@code MediaRecorder} writes a
 * generic {@code isom}/{@code mp42} brand. The sniffer therefore needs the
 * {@code moov} box, which may sit at the end of the file. The leading window
 * is {@value #SNIFF_PREFIX_LENGTH} bytes; when it holds an {@code ftyp} box
 * whose kind is still undecided, a bounded trailing window of
 * {@value #SNIFF_SUFFIX_LENGTH} bytes is read through a second handle so the
 * body stream stays intact. Both windows are capped, so a large file is never
 * buffered whole.</p>
 *
 * <p><b>Authorization (phase-09 fix):</b> before serving, the key is checked
 * against {@code message_attachments} via
 * {@link AttachmentService#assertStorageKeyReadable}: a key belonging to a
 * message attachment is served only to its owner or to a member of the bound
 * conversation (unbound uploads are owner-only). Keys with no attachment row —
 * avatars and other profile-public files — keep working for any authenticated
 * user, since they carry no membership concept.</p>
 *
 * <p><b>Range support:</b> the file is streamed through a {@link Resource},
 * never read whole into heap. A {@code Range: bytes=...} request is answered
 * with {@code 206 Partial Content} and the matching {@code Content-Range};
 * only the first range of a multi-range request is honoured (RFC 9110 allows
 * a server to ignore the rest). A missing range returns {@code 200} with
 * {@code Accept-Ranges: bytes}; an unsatisfiable or malformed range returns
 * {@code 416} with {@code Content-Range: bytes &#42;/size}.</p>
 *
 * <p><b>Bounded upstream work:</b> the storage service is asked only for the
 * windows this class needs — a bounded leading sniff, an optional bounded
 * trailing sniff, and the exact response slice. A suffix or short range
 * request is therefore one small backend range read instead of a full-object
 * traversal. Every handle opened here is closed on the error paths and by the
 * resource when the response completes or the client disconnects.</p>
 */
@RestController
public class FileController {

    private static final String FILES_PREFIX = "/api/v1/files/";
    private static final String BYTES_UNIT = "bytes";
    private static final int SNIFF_PREFIX_LENGTH = 64 * 1024;
    private static final int SNIFF_SUFFIX_LENGTH = 64 * 1024;

    private final StorageService storageService;
    private final AttachmentService attachmentService;

    public FileController(StorageService storageService, AttachmentService attachmentService) {
        this.storageService = storageService;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/api/v1/files/**")
    public ResponseEntity<?> serve(HttpServletRequest request,
                                   @AuthenticationPrincipal JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        String storageKey = extractKey(request);
        // Member-check fires only for keys bound to a message attachment row.
        attachmentService.assertStorageKeyReadable(storageKey, principal.userId());

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            return serveRange(storageKey, rangeHeader);
        }
        return serveWhole(storageKey);
    }

    /** Streams the whole object through one backend read. */
    private ResponseEntity<?> serveWhole(String storageKey) {
        StorageService.StreamHandle handle = storageService.open(storageKey);
        final byte[] prefix;
        final byte[] suffix;
        try {
            prefix = handle.content().readNBytes(SNIFF_PREFIX_LENGTH);
            suffix = sniffSuffix(storageKey, handle.sizeBytes(), prefix);
        } catch (IOException ex) {
            closeQuietly(handle);
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        } catch (RuntimeException ex) {
            closeQuietly(handle);
            throw ex;
        }
        String contentType = contentType(prefix, suffix);
        InputStream bodyStream = new SequenceInputStream(
                new ByteArrayInputStream(prefix), handle.content());
        long size = handle.sizeBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                .contentLength(size)
                .body(new SniffedResource(bodyStream, size));
    }

    /**
     * Serves one byte range. The size and the sniff window come from a single
     * bounded leading read, and the body is one exact backend range read, so
     * the skipped bytes never cross the network.
     */
    private ResponseEntity<?> serveRange(String storageKey, String rangeHeader) {
        StorageService.PrefixRead prefix = storageService.readPrefix(storageKey, SNIFF_PREFIX_LENGTH);
        long size = prefix.sizeBytes();
        final long[] range;
        try {
            range = parseRange(rangeHeader, size);
        } catch (IllegalArgumentException ex) {
            // Nothing beyond the bounded sniff was read, and no body handle
            // was opened: respond 416 without touching the rest of the file.
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                    .build();
        }
        byte[] suffix = sniffSuffix(storageKey, size, prefix.bytes());
        String contentType = contentType(prefix.bytes(), suffix);
        long start = range[0];
        long end = range[1];
        long count = end - start + 1;
        StorageService.StreamHandle handle = storageService.openRange(storageKey, start, count);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(contentType))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                .contentLength(count)
                // The resource carries exactly the requested bytes, so the
                // converter writes the slice without buffering the file.
                .body(new SniffedResource(handle.content(), count));
    }

    private byte[] sniffSuffix(String storageKey, long size, byte[] prefix) {
        if (!MagicBytes.isIsoBmffUndetermined(prefix) || size <= prefix.length) {
            return null;
        }
        return storageService.readSuffix(storageKey, size, SNIFF_SUFFIX_LENGTH);
    }

    private static String contentType(byte[] prefix, byte[] suffix) {
        String contentType = MagicBytes.sniff(prefix, suffix);
        return contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
    }

    /**
     * Parses a single-byte-range {@code Range} header.
     *
     * @param header the raw {@code Range} header value
     * @param size   the current representation length in bytes
     * @return {@code [start, end]} inclusive offsets
     * @throws IllegalArgumentException when the header is malformed or the
     *                                  requested range cannot be satisfied
     */
    private static long[] parseRange(String header, long size) {
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
        return new long[] {start, end};
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed range number", ex);
        }
    }

    private static void closeQuietly(StorageService.StreamHandle handle) {
        try {
            handle.close();
        } catch (IOException ignored) {
            // Best-effort release of the handle on an error path.
        }
    }

    private static String extractKey(HttpServletRequest request) {
        String path = null;
        Object mapped = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (mapped instanceof String value) {
            path = value;
        }
        if (path == null) {
            path = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
        }
        if (path == null || !path.startsWith(FILES_PREFIX)) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        String key = path.substring(FILES_PREFIX.length());
        if (key.isBlank() || key.contains("..") || key.contains("\\")) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        return key;
    }

    /**
     * A stream-backed resource with a known length, so Spring's
     * {@code ResourceHttpMessageConverter} can set {@code Content-Length}
     * without buffering the file.
     */
    private static final class SniffedResource extends InputStreamResource {

        private final long length;

        SniffedResource(InputStream inputStream, long length) {
            super(inputStream);
            this.length = length;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public String getFilename() {
            return null;
        }
    }
}

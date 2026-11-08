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
 * Serves stored files from {@code ${nexachat.storage.root}} as inline content.
 *
 * <p>The storage key is the request path after {@code /api/v1/files/}. It is
 * normalized and required to stay inside the storage root before any read, so
 * a crafted key cannot escape the upload directory. The response Content-Type
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

        StorageService.StreamHandle handle = storageService.open(storageKey);
        final byte[] prefix;
        final byte[] suffix;
        try {
            prefix = handle.content().readNBytes(SNIFF_PREFIX_LENGTH);
            suffix = MagicBytes.isIsoBmffUndetermined(prefix)
                    ? readSuffix(storageService, storageKey, handle.sizeBytes(), prefix.length)
                    : null;
        } catch (IOException ex) {
            closeQuietly(handle);
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
        String contentType = MagicBytes.sniff(prefix, suffix);
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        InputStream bodyStream = new SequenceInputStream(
                new ByteArrayInputStream(prefix), handle.content());
        long size = handle.sizeBytes();

        long[] range = null;
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            try {
                range = parseRange(rangeHeader, size);
            } catch (IllegalArgumentException ex) {
                closeQuietly(handle);
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                        .build();
            }
        }

        if (range == null) {
            Resource resource = new SniffedResource(bodyStream, size);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                    .contentLength(size)
                    .body(resource);
        }
        long start = range[0];
        long count = range[1] - start + 1;
        // The resource itself carries exactly the requested bytes, so the
        // converter writes the range without buffering the file.
        final Resource resource;
        try {
            resource = new SniffedResource(
                    new BoundedInputStream(bodyStream, start, count), count);
        } catch (IOException ex) {
            closeQuietly(handle);
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(contentType))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + start + "-" + range[1] + "/" + size)
                .contentLength(count)
                .body(resource);
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

    /**
     * Reads at most {@value #SNIFF_SUFFIX_LENGTH} trailing bytes of the stored
     * file through a separate handle, so the serving handle stays positioned
     * right after the prefix and can still stream the whole body. Returns
     * {@code null} when the file fits in the prefix already.
     */
    private static byte[] readSuffix(StorageService storageService, String storageKey,
                                     long size, int prefixLength) throws IOException {
        long remaining = size - prefixLength;
        if (remaining <= 0) {
            return null;
        }
        int suffixLength = (int) Math.min(remaining, SNIFF_SUFFIX_LENGTH);
        try (StorageService.StreamHandle suffixHandle = storageService.open(storageKey)) {
            // The suffix is the last suffixLength bytes of the file.
            suffixHandle.content().skipNBytes(size - suffixLength);
            return suffixHandle.content().readNBytes(suffixLength);
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
     * Skips {@code skip} bytes and then yields at most {@code limit} bytes, so
     * one stored stream can back both the full-file and the range responses.
     */
    private static final class BoundedInputStream extends java.io.FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream in, long skip, long limit) throws IOException {
            super(in);
            in.skipNBytes(skip);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read();
            if (read >= 0) {
                remaining--;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }
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

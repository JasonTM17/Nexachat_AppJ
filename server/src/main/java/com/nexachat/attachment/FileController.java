package com.nexachat.attachment;

import com.nexachat.common.MagicBytes;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Serves stored files from {@code ${nexachat.storage.root}} as inline content.
 *
 * <p>The storage key is the request path after {@code /api/v1/files/}. It is
 * normalized and required to stay inside the storage root before any read, so
 * a crafted key cannot escape the upload directory. The response Content-Type
 * is re-sniffed from the stored bytes — the same sniff the store path applied
 * — with {@code application/octet-stream} as the final fallback.</p>
 */
@RestController
public class FileController {

    private static final String FILES_PREFIX = "/api/v1/files/";

    private final Path root;

    public FileController(
            @Value("${nexachat.storage.root:./data/uploads}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
    }

    @GetMapping("/api/v1/files/**")
    public ResponseEntity<byte[]> serve(HttpServletRequest request) {
        String storageKey = extractKey(request);
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(target);
        } catch (NoSuchFileException ex) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
        } catch (IOException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Failed to read stored file");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(sniffedOrOctetStream(bytes)))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(bytes);
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

    private static String sniffedOrOctetStream(byte[] bytes) {
        String mimeType = MagicBytes.sniff(bytes);
        return mimeType != null ? mimeType : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}

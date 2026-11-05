package com.nexachat.attachment;

import com.nexachat.common.MagicBytes;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Serves stored files from {@code ${nexachat.storage.root}} as inline content.
 *
 * <p>The storage key is the request path after {@code /api/v1/files/}. It is
 * normalized and required to stay inside the storage root before any read, so
 * a crafted key cannot escape the upload directory. The response Content-Type
 * is re-sniffed from the stored bytes — the same sniff the store path applied
 * — with {@code application/octet-stream} as the final fallback.</p>
 *
 * <p><b>Authorization (phase-09 fix):</b> before serving, the key is checked
 * against {@code message_attachments} via
 * {@link AttachmentService#assertStorageKeyReadable}: a key belonging to a
 * message attachment is served only to its owner or to a member of the bound
 * conversation (unbound uploads are owner-only). Keys with no attachment row —
 * avatars and other profile-public files — keep working for any authenticated
 * user, since they carry no membership concept.
 */
@RestController
public class FileController {

    private static final String FILES_PREFIX = "/api/v1/files/";

    private final StorageService storageService;
    private final AttachmentService attachmentService;

    public FileController(StorageService storageService, AttachmentService attachmentService) {
        this.storageService = storageService;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/api/v1/files/**")
    public ResponseEntity<byte[]> serve(HttpServletRequest request,
                                        @AuthenticationPrincipal JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        String storageKey = extractKey(request);
        // Member-check fires only for keys bound to a message attachment row.
        attachmentService.assertStorageKeyReadable(storageKey, principal.userId());
        byte[] bytes = storageService.read(storageKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(sniffedOrOctetStream(bytes)))
                .header("X-Content-Type-Options", "nosniff")
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

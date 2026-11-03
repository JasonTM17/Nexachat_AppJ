package com.nexachat.attachment;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.dto.AttachmentResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Attachment REST endpoints (phase-09 multimedia, plan §10.2):
 *
 * <ul>
 *   <li>{@code POST /api/v1/attachments} — multipart upload (part
 *       {@code file} plus optional {@code width}, {@code height},
 *       {@code duration} form fields). Requires a Bearer token; the row is
 *       owned by the caller and stays unbound until a send claims it via
 *       {@code attachmentIds}. 201 on success; 413 {@code FILE_TOO_LARGE},
 *       415 {@code MIME_NOT_ALLOWED} on rejection.</li>
 *   <li>{@code GET /api/v1/attachments/{id}/content} — streams the bytes
 *       inline with the sniffed MIME as Content-Type and a sanitized
 *       {@code Content-Disposition} filename. Member-checked authZ (owner or
 *       member of the bound conversation; unbound → owner only):
 *       404 {@code ATTACHMENT_NOT_FOUND}, 403 {@code NOT_MEMBER}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttachmentResponse> upload(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Integer width,
            @RequestParam(required = false) Integer height,
            @RequestParam(required = false) Integer duration) {
        AttachmentResponse response = attachmentService.upload(
                requireUserId(principal), file, width, height, duration);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable("id") UUID attachmentId) {
        AttachmentService.AttachmentContent content =
                attachmentService.loadContent(attachmentId, requireUserId(principal));
        // The persisted mime IS the upload-time sniff — never re-trust the
        // client-declared type; the filename is re-sanitized before it enters
        // the header even though upload already cleaned it once.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.attachment().getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(AttachmentService.sanitizeOriginalName(
                                content.attachment().getOriginalName()), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(content.bytes());
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

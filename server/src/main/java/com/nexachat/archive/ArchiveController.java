package com.nexachat.archive;

import com.nexachat.archive.dto.ArchiveResponse;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Storage-browser endpoint:
 * {@code GET /api/v1/conversations/{id}/archive?type=…&senderId=…&from=…&to=…
 * &limit=…&cursor=…}.
 *
 * <p>Read-only and member-scoped. Media and files are derived from message
 * attachments; links are extracted from TEXT content. The server never fetches
 * a link.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/archive")
public class ArchiveController {

    private final ArchiveService archiveService;

    public ArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @GetMapping
    public ArchiveResponse browse(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID senderId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return archiveService.browse(
                requireUserId(principal), conversationId, type, senderId, from, to, limit, cursor);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

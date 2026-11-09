package com.nexachat.conversation;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.InboxEntry;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/conversations} — the caller's inbox, ordered with the
 * caller's pinned conversations first and then {@code lastMessageSeq DESC}
 * (conversations without messages last).
 *
 * <p>{@code archived} selects the view: omitted or {@code false} returns the
 * default inbox (archived conversations hidden), {@code true} returns only
 * archived conversations.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationInboxController {

    private final InboxService inboxService;

    public ConversationInboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    public ResponseEntity<List<InboxEntry>> inbox(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) UUID labelId) {
        UUID userId = requireUserId(principal);
        if (labelId == null) {
            if (limit == null && cursor == null) {
                return ResponseEntity.ok(inboxService.listInbox(userId, archived));
            }
            InboxService.InboxPage existingPage =
                    inboxService.listInbox(userId, limit, cursor, archived);
            return pagedResponse(existingPage);
        }
        if (limit == null && cursor == null) {
            return ResponseEntity.ok(inboxService.listInbox(userId, archived, labelId));
        }
        InboxService.InboxPage page = inboxService.listInbox(userId, limit, cursor, archived, labelId);
        return pagedResponse(page);
    }

    /** Source-compatible overload for controller unit tests and direct callers. */
    public ResponseEntity<List<InboxEntry>> inbox(
            JwtPrincipal principal, Integer limit, String cursor, Boolean archived) {
        return inbox(principal, limit, cursor, archived, null);
    }

    private static ResponseEntity<List<InboxEntry>> pagedResponse(InboxService.InboxPage page) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (page.nextCursor() != null) {
            response.header("X-Next-Cursor", page.nextCursor());
        }
        return response.body(page.items());
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

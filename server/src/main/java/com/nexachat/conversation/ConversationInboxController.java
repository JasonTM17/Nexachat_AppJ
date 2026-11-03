package com.nexachat.conversation;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.InboxEntry;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/conversations} — the caller's inbox, ordered
 * {@code lastMessageSeq DESC} (conversations without messages last).
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationInboxController {

    private final InboxService inboxService;

    public ConversationInboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    public List<InboxEntry> inbox(@AuthenticationPrincipal JwtPrincipal principal) {
        return inboxService.listInbox(requireUserId(principal));
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

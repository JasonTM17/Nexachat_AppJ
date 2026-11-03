package com.nexachat.conversation;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.ConversationResponse;
import com.nexachat.conversation.dto.OpenDirectRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Conversation endpoints (plan §10.2) — DIRECT path only; group endpoints
 * land in a later phase. POST /conversations/direct is idempotent: 201 on
 * create, 200 when the pair already has a conversation (including a lost
 * direct_key race) — the body is identical either way.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/direct")
    public ResponseEntity<ConversationResponse> openDirect(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody OpenDirectRequest request) {
        ConversationService.OpenDirectResult result =
                conversationService.openDirect(requireUserId(principal), request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.body());
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

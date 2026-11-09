package com.nexachat.conversation;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Per-member conversation pin routes under {@code /api/v1/conversations}.
 *
 * <p>Both verbs are member-only, idempotent and return an empty {@code 204}:
 * the state itself is read back from the inbox (or delta sync), so there is no
 * response body to keep in sync. {@code PUT} fails {@code 409
 * CONVERSATION_PIN_LIMIT} when a NEW pin would exceed the caller's five-pin
 * ceiling.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationPinController {

    private final ConversationPinService pinService;

    public ConversationPinController(ConversationPinService pinService) {
        this.pinService = pinService;
    }

    @PutMapping("/{conversationId}/pin")
    public ResponseEntity<Void> pin(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        pinService.pin(requireUserId(principal), conversationId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{conversationId}/pin")
    public ResponseEntity<Void> unpin(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        pinService.unpin(requireUserId(principal), conversationId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

package com.nexachat.summary;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Per-conversation AI privacy opt-out for the caller's own messages. PUT and
 * DELETE are idempotent and return the resulting caller-scoped state; GET
 * reads it without changing anything.
 */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/conversations")
public class ConversationAiOptOutController {

    private final ConversationAiOptOutService service;

    public ConversationAiOptOutController(ConversationAiOptOutService service) {
        this.service = service;
    }

    @PutMapping("/{conversationId}/ai-opt-out")
    public ConversationAiOptOutResponse optOut(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return service.optOut(conversationId, requireUserId(principal));
    }

    @DeleteMapping("/{conversationId}/ai-opt-out")
    public ConversationAiOptOutResponse clear(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return service.clear(conversationId, requireUserId(principal));
    }

    @GetMapping("/{conversationId}/ai-opt-out")
    public ConversationAiOptOutResponse read(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return service.read(conversationId, requireUserId(principal));
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

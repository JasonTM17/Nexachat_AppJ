package com.nexachat.message;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.dto.ReactRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reaction REST endpoint (phase-10, plan §10.2):
 *
 * <ul>
 *   <li>{@code POST /{id}/reactions {emoji}} → 204, TOGGLE semantics —
 *       members of the message's conversation only. The post-toggle state
 *       reaches clients through the {@code REACTION_UPDATED} topic frame;
 *       the 204 deliberately carries no body.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/messages")
public class ReactionController {

    private final ReactionService reactionService;

    public ReactionController(ReactionService reactionService) {
        this.reactionService = reactionService;
    }

    @PostMapping("/{messageId}/reactions")
    public ResponseEntity<Void> react(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID messageId,
            @Valid @RequestBody ReactRequest request) {
        reactionService.toggle(requireUserId(principal), messageId, request);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

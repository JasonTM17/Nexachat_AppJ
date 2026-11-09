package com.nexachat.label;

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

/** Member-only assignment endpoints for private labels. */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/labels/{labelId}")
public class ConversationLabelController {

    private final LabelService labelService;

    public ConversationLabelController(LabelService labelService) {
        this.labelService = labelService;
    }

    @PutMapping
    public ResponseEntity<Void> assign(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID conversationId,
                                       @PathVariable UUID labelId) {
        labelService.assign(requireUserId(principal), conversationId, labelId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unassign(@AuthenticationPrincipal JwtPrincipal principal,
                                         @PathVariable UUID conversationId,
                                         @PathVariable UUID labelId) {
        labelService.unassign(requireUserId(principal), conversationId, labelId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

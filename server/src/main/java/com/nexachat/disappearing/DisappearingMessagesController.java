package com.nexachat.disappearing;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.disappearing.dto.DisappearingRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code PUT /api/v1/conversations/{id}/disappearing} — sets or clears the
 * conversation's disappearing-message timer. Returns the same
 * {@link ConversationUpdatePayload} snapshot broadcast on the topic, so the
 * acting client and the fan-out consumers apply one shape.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class DisappearingMessagesController {

    private final DisappearingMessagesService service;

    public DisappearingMessagesController(DisappearingMessagesService service) {
        this.service = service;
    }

    @PutMapping("/{conversationId}/disappearing")
    public ConversationUpdatePayload set(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody DisappearingRequest request) {
        return service.setTimer(requireUserId(principal), conversationId, request);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

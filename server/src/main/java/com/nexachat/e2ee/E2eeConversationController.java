package com.nexachat.e2ee;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.e2ee.dto.ConversationE2eeStateResponse;
import com.nexachat.e2ee.dto.SetE2eeRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** E2EE state of a DIRECT conversation: read, or toggle with a member-only PUT. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/conversations/{conversationId}/e2ee")
public class E2eeConversationController {

    private final E2eeConversationService e2eeService;

    public E2eeConversationController(E2eeConversationService e2eeService) {
        this.e2eeService = e2eeService;
    }

    @GetMapping
    public ConversationE2eeStateResponse get(@AuthenticationPrincipal JwtPrincipal principal,
                                             @PathVariable UUID conversationId) {
        return e2eeService.getState(requireUserId(principal), conversationId);
    }

    @PutMapping
    public ConversationE2eeStateResponse set(@AuthenticationPrincipal JwtPrincipal principal,
                                             @PathVariable UUID conversationId,
                                             @Valid @RequestBody SetE2eeRequest request) {
        return e2eeService.setEnabled(
                requireUserId(principal), conversationId, request.enabled());
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

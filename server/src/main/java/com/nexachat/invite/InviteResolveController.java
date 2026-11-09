package com.nexachat.invite;

import com.nexachat.config.ClientIpResolver;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.invite.dto.InvitePreviewResponse;
import com.nexachat.invite.dto.JoinResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public endpoints (requiring user authentication) for resolving invite links
 * and requesting to join groups.
 */
@RestController
@RequestMapping("/api/v1/invite-links/{token}")
public class InviteResolveController {

    private final InviteService inviteService;
    private final ClientIpResolver clientIpResolver;

    public InviteResolveController(InviteService inviteService, ClientIpResolver clientIpResolver) {
        this.inviteService = inviteService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    public InvitePreviewResponse resolvePreview(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable String token,
            HttpServletRequest request) {
        UUID userId = requireUserId(principal);
        String clientIp = clientIpResolver.rateLimitClientIp(request);
        return inviteService.resolvePreview(userId, token, clientIp);
    }

    @PostMapping("/join")
    public ResponseEntity<JoinResponse> join(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable String token,
            HttpServletRequest request) {
        UUID userId = requireUserId(principal);
        String clientIp = clientIpResolver.rateLimitClientIp(request);
        JoinResponse response = inviteService.join(userId, token, clientIp);
        HttpStatus status = response.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

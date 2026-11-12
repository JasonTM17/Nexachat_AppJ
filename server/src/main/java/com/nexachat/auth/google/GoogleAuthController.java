package com.nexachat.auth.google;

import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.auth.google.dto.GoogleLinkRequest;
import com.nexachat.auth.google.dto.GoogleNonceResponse;
import com.nexachat.auth.google.dto.GoogleSignInRequest;
import com.nexachat.auth.google.dto.GoogleUnlinkRequest;
import com.nexachat.auth.google.dto.LinkedIdentitiesResponse;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * "Sign in with Google" endpoints.
 *
 * <p>{@code POST /google/nonce} and {@code POST /google} are public (permitAll
 * in SecurityConfig); the identity routes require a Bearer token. All of them
 * answer 404 while {@code nexachat.google.signin.enabled} is false.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class GoogleAuthController {

    private final GoogleSignInService googleSignInService;
    private final GoogleNonceService nonceService;

    public GoogleAuthController(GoogleSignInService googleSignInService,
                                GoogleNonceService nonceService) {
        this.googleSignInService = googleSignInService;
        this.nonceService = nonceService;
    }

    @PostMapping("/google/nonce")
    public GoogleNonceResponse signInNonce() {
        googleSignInService.requireEnabled();
        return toResponse(nonceService.issue(GoogleNonceService.PURPOSE_SIGNIN, null, null));
    }

    /** Nonce for the authenticated link flow; the public nonce cannot be used here. */
    @PostMapping("/identities/google/nonce")
    public GoogleNonceResponse linkNonce(@AuthenticationPrincipal JwtPrincipal principal,
                                         HttpServletRequest request) {
        return toResponse(googleSignInService.issueLinkNonce(
                requireUserId(principal), requireAccessTokenId(request)));
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> signIn(@Valid @RequestBody GoogleSignInRequest request,
                                               @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        GoogleSignInResult result = googleSignInService.signIn(request, userAgent);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/identities")
    public LinkedIdentitiesResponse identities(@AuthenticationPrincipal JwtPrincipal principal) {
        return googleSignInService.list(requireUserId(principal));
    }

    @PostMapping("/identities/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void link(@AuthenticationPrincipal JwtPrincipal principal,
                     HttpServletRequest servletRequest,
                     @Valid @RequestBody GoogleLinkRequest request) {
        googleSignInService.link(
                requireUserId(principal), requireAccessTokenId(servletRequest), request);
    }

    @DeleteMapping("/identities/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal JwtPrincipal principal,
                       @Valid @RequestBody GoogleUnlinkRequest request) {
        googleSignInService.unlink(requireUserId(principal), request);
    }

    private static GoogleNonceResponse toResponse(GoogleNonceService.IssuedNonce issued) {
        return new GoogleNonceResponse(issued.nonce(), issued.expiresInSeconds());
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }

    private static String requireAccessTokenId(HttpServletRequest request) {
        Object value = request.getAttribute(com.nexachat.auth.JwtAuthFilter.ACCESS_TOKEN_ID_ATTRIBUTE);
        if (value instanceof String tokenId && !tokenId.isBlank()) {
            return tokenId;
        }
        throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
    }
}

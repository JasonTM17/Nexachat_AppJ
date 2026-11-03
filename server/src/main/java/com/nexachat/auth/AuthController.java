package com.nexachat.auth;

import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.auth.dto.ChangePasswordRequest;
import com.nexachat.auth.dto.LoginRequest;
import com.nexachat.auth.dto.LogoutRequest;
import com.nexachat.auth.dto.RefreshRequest;
import com.nexachat.auth.dto.RegisterRequest;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Auth endpoints (plan §10.2). register/login/refresh are permitAll;
 * logout/logout-all/password require Bearer (enforced in SecurityConfig).
 * The {@code User-Agent} header is recorded as the session's device_label.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                 @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        return authService.register(request, userAgent);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                              @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        return authService.login(request, userAgent);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request,
                                @RequestHeader(name = "User-Agent", required = false) String userAgent) {
        return authService.refresh(request, userAgent);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal JwtPrincipal principal,
                       @Valid @RequestBody LogoutRequest request) {
        authService.logout(requireUserId(principal), request.refreshToken());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal JwtPrincipal principal) {
        authService.logoutAll(requireUserId(principal));
    }

    @PatchMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal JwtPrincipal principal,
                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(requireUserId(principal), request);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

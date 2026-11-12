package com.nexachat.user;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.dto.DeleteAccountRequest;
import com.nexachat.user.dto.EmailVerificationResponse;
import com.nexachat.user.dto.MyProfileResponse;
import com.nexachat.user.dto.RecoveryEmailRequest;
import com.nexachat.user.dto.UpdateProfileRequest;
import com.nexachat.user.dto.UserResponse;
import com.nexachat.user.dto.VerifyEmailRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-service profile endpoints (plan §10.2): GET + PATCH /api/v1/users/me,
 * plus the rate-limited GET /api/v1/users/search (30 req/min per user — see
 * {@link UserSearchRateLimitInterceptor}).
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final AccountDeletionService accountDeletionService;
    private final EmailRecoveryService emailRecoveryService;

    public UserController(UserService userService,
                          AccountDeletionService accountDeletionService,
                          EmailRecoveryService emailRecoveryService) {
        this.userService = userService;
        this.accountDeletionService = accountDeletionService;
        this.emailRecoveryService = emailRecoveryService;
    }

    @GetMapping("/me")
    public MyProfileResponse me(@AuthenticationPrincipal JwtPrincipal principal) {
        return MyProfileResponse.from(userService.getById(requireUserId(principal)));
    }

    @PatchMapping("/me")
    public UserResponse updateMe(@AuthenticationPrincipal JwtPrincipal principal,
                                 @Valid @RequestBody UpdateProfileRequest request) {
        return UserResponse.from(userService.updateDisplayName(requireUserId(principal), request.displayName()));
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal JwtPrincipal principal,
                         @Valid @RequestBody DeleteAccountRequest request) {
        accountDeletionService.deleteAccount(requireUserId(principal), request.password(), request.code());
    }

    /**
     * Sends a DELETE_ACCOUNT code to the caller's own verified email.
     * Accounts that already have a password use {@code DELETE /users/me} instead.
     */
    @PostMapping("/me/deletion-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestDeletionCode(@AuthenticationPrincipal JwtPrincipal principal) {
        emailRecoveryService.requestDeletionCode(requireUserId(principal));
    }

    @PutMapping("/me/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestEmailVerification(@AuthenticationPrincipal JwtPrincipal principal,
                                         @Valid @RequestBody RecoveryEmailRequest request) {
        emailRecoveryService.requestVerification(requireUserId(principal), request.email());
    }

    @PostMapping("/me/email/verify")
    public EmailVerificationResponse verifyEmail(@AuthenticationPrincipal JwtPrincipal principal,
                                                 @Valid @RequestBody VerifyEmailRequest request) {
        return new EmailVerificationResponse(
                emailRecoveryService.verify(requireUserId(principal), request.code()), true);
    }

    @DeleteMapping("/me/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmail(@AuthenticationPrincipal JwtPrincipal principal) {
        emailRecoveryService.remove(requireUserId(principal));
    }

    /**
     * User discovery: {@code ?phone=} exact E.164, {@code ?email=} exact verified
     * email (caller excluded), or {@code ?q=} displayName prefix (≤20, caller
     * excluded). Exactly one query param is required — enforced in the service.
     */
    @GetMapping("/search")
    public List<UserResponse> search(@AuthenticationPrincipal JwtPrincipal principal,
                                     @RequestParam(name = "phone", required = false) String phone,
                                     @RequestParam(name = "email", required = false) String email,
                                     @RequestParam(name = "q", required = false) String q) {
        return userService.search(requireUserId(principal), phone, email, q);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

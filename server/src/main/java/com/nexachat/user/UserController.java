package com.nexachat.user;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.dto.UpdateProfileRequest;
import com.nexachat.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Self-service profile endpoints (plan §10.2): GET + PATCH /api/v1/users/me.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal JwtPrincipal principal) {
        return UserResponse.from(userService.getById(requireUserId(principal)));
    }

    @PatchMapping("/me")
    public UserResponse updateMe(@AuthenticationPrincipal JwtPrincipal principal,
                                 @Valid @RequestBody UpdateProfileRequest request) {
        return UserResponse.from(userService.updateDisplayName(requireUserId(principal), request.displayName()));
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

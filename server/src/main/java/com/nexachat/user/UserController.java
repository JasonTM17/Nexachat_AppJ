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
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * User discovery: {@code ?phone=} does an exact E.164 lookup (list of
     * 0..1), {@code ?q=} does a displayName prefix search (≤20, caller
     * excluded). Exactly one param is required — enforced in the service.
     */
    @GetMapping("/search")
    public List<UserResponse> search(@AuthenticationPrincipal JwtPrincipal principal,
                                     @RequestParam(name = "phone", required = false) String phone,
                                     @RequestParam(name = "q", required = false) String q) {
        return userService.search(requireUserId(principal), phone, q);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

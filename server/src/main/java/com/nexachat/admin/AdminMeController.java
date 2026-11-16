package com.nexachat.admin;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The admin-web session bootstrap: who the caller is, with the effective
 * server-side role. Login there calls the shared auth endpoint and then this
 * to verify the caller actually has staff privileges.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminMeController {

    private final UserRepository userRepository;

    public AdminMeController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record MeResponse(UUID userId, String role, String displayName) {
    }

    @GetMapping("/me")
    public MeResponse me() {
        JwtPrincipal principal = AdminGuard.requireStaff();
        return userRepository.findById(principal.userId())
                .map(user -> new MeResponse(user.getId(), AdminGuard.role().name(),
                        user.getDisplayName()))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
    }
}

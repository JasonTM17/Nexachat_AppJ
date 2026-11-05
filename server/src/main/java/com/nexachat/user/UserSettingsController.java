package com.nexachat.user;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.dto.UpdateUserSettingsRequest;
import com.nexachat.user.dto.UserSettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/settings")
public class UserSettingsController {

    private final UserSettingsService settingsService;

    public UserSettingsController(UserSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public UserSettingsResponse get(@AuthenticationPrincipal JwtPrincipal principal) {
        return settingsService.get(requireUserId(principal));
    }

    @PatchMapping
    public UserSettingsResponse update(@AuthenticationPrincipal JwtPrincipal principal,
                                       @Valid @RequestBody UpdateUserSettingsRequest request) {
        return settingsService.updateSettings(requireUserId(principal),
                request.showPresence(), request.aiSummaryConsent());
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

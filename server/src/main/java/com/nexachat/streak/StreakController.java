package com.nexachat.streak;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.streak.dto.StreakResponse;
import com.nexachat.streak.dto.StreakSummaryResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Streak REST endpoints, all caller-scoped:
 *
 * <ul>
 *   <li>{@code GET /streaks/me} - the app-wide check-in streak plus the reminder opt-in;</li>
 *   <li>{@code POST /streaks/check-in} - records "opened the app today" (idempotent, 200);</li>
 *   <li>{@code GET /conversations/{id}/streak} - a member's view of one chat's chain.</li>
 * </ul>
 */
@RestController
@Profile("!test")
public class StreakController {

    private final StreakService streakService;

    public StreakController(StreakService streakService) {
        this.streakService = streakService;
    }

    @GetMapping("/api/v1/streaks/me")
    public StreakSummaryResponse me(@AuthenticationPrincipal JwtPrincipal principal) {
        return streakService.summary(requireUserId(principal));
    }

    @PostMapping("/api/v1/streaks/check-in")
    public StreakSummaryResponse checkIn(@AuthenticationPrincipal JwtPrincipal principal) {
        return streakService.checkIn(requireUserId(principal));
    }

    @GetMapping("/api/v1/conversations/{conversationId}/streak")
    public StreakResponse conversation(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID conversationId) {
        return streakService.conversationStreak(requireUserId(principal), conversationId);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

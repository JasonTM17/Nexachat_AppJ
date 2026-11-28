package com.nexachat.social;

import com.nexachat.admin.AdminGuard;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only control surface for the Social runtime switches and the live
 * announcement CMS. Unlike the moderation routes — which moderators share —
 * every route here, reads included, requires the ADMIN role because these
 * controls change what the whole user base sees. All mutations carry the
 * {@code expectedRevision} the caller loaded; a stale token answers
 * {@code 409 SOCIAL_SETTINGS_CONFLICT} without side effects, and every
 * successful change is audited in the same transaction.
 */
@RestController
@RequestMapping("/api/v1/admin/social/settings")
public class AdminSocialSettingsController {

    public record FlagsRequest(@NotNull Long expectedRevision,
                               @NotNull Boolean webEnabled,
                               @NotNull Boolean videoUploadsEnabled) {
    }

    public record DraftRequest(@NotNull Long expectedRevision,
                               @NotNull String titleVi,
                               @NotNull String bodyVi,
                               @NotNull String titleEn,
                               @NotNull String bodyEn) {
    }

    public record RevisionRequest(@NotNull Long expectedRevision) {
    }

    private final SocialSettingsService settings;
    private final ClientIpResolver clientIpResolver;

    public AdminSocialSettingsController(SocialSettingsService settings,
                                         ClientIpResolver clientIpResolver) {
        this.settings = settings;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    public SocialSettingsService.AdminState state() {
        AdminGuard.requireAdmin();
        return settings.adminState();
    }

    @PatchMapping("/flags")
    public SocialSettingsService.AdminState updateFlags(@Valid @RequestBody FlagsRequest body,
                                                        HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireAdmin();
        return settings.updateFlags(body.expectedRevision(), body.webEnabled(), body.videoUploadsEnabled(),
                actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PutMapping("/announcement/draft")
    public SocialSettingsService.AdminState saveDraft(@Valid @RequestBody DraftRequest body,
                                                      HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireAdmin();
        return settings.saveDraft(body.expectedRevision(), body.titleVi(), body.bodyVi(),
                body.titleEn(), body.bodyEn(), actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PostMapping("/announcement/publish")
    public SocialSettingsService.AdminState publish(@Valid @RequestBody RevisionRequest body,
                                                    HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireAdmin();
        return settings.publish(body.expectedRevision(), actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PostMapping("/announcement/unpublish")
    public SocialSettingsService.AdminState unpublish(@Valid @RequestBody RevisionRequest body,
                                                      HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireAdmin();
        return settings.unpublish(body.expectedRevision(), actor.userId(), AdminGuard.role(), clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.rateLimitClientIp(request);
    }
}

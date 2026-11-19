package com.nexachat.feed.admin;

import com.nexachat.admin.AdminAuditService;
import com.nexachat.admin.AdminGuard;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Staff moderation of the public feed ({@code /api/v1/admin/feed/**}). The
 * {@code ADMIN|MODERATOR} gate is applied by the security filter chain; every
 * action is audited through {@link AdminAuditService}.
 */
@RestController
@RequestMapping("/api/v1/admin/feed")
public class AdminFeedController {

    private final AdminFeedService service;
    private final ClientIpResolver clientIpResolver;

    public AdminFeedController(AdminFeedService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ResolveRequest(@NotBlank String status) {
    }

    @GetMapping("/reports")
    public AdminAuditService.Page<AdminFeedReportResponse> reports(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        AdminGuard.requireStaff();
        return service.reports(status, page, size);
    }

    @PostMapping("/posts/{postId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hidePost(@PathVariable UUID postId,
                         @Valid @RequestBody ReasonRequest body,
                         HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.hidePost(actor.userId(), AdminGuard.role(), postId, body.reason(),
                clientIp(request));
    }

    @PostMapping("/posts/{postId}/unhide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unhidePost(@PathVariable UUID postId,
                           @Valid @RequestBody(required = false) ReasonRequest body,
                           HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.unhidePost(actor.userId(), AdminGuard.role(), postId,
                body == null ? null : body.reason(), clientIp(request));
    }

    @PostMapping("/comments/{commentId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hideComment(@PathVariable UUID commentId,
                            @Valid @RequestBody ReasonRequest body,
                            HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.hideComment(actor.userId(), AdminGuard.role(), commentId, body.reason(),
                clientIp(request));
    }

    @PostMapping("/comments/{commentId}/unhide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unhideComment(@PathVariable UUID commentId,
                              @Valid @RequestBody(required = false) ReasonRequest body,
                              HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.unhideComment(actor.userId(), AdminGuard.role(), commentId,
                body == null ? null : body.reason(), clientIp(request));
    }

    @PostMapping("/reports/{reportId}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolveReport(@PathVariable UUID reportId,
                              @Valid @RequestBody ResolveRequest body,
                              HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.resolveReport(actor.userId(), AdminGuard.role(), reportId, body.status(),
                clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.rateLimitClientIp(request);
    }
}

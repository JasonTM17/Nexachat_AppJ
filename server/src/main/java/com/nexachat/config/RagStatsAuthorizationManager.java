package com.nexachat.config;

import com.nexachat.common.PhoneNormalizer;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Authorizes operator-telemetry endpoints to the phones listed in
 * {@code nexachat.security.admin-phones}.
 *
 * <p>The system has no role column or role claim yet; introducing full RBAC is
 * outside this hardening pass. A configured allow-list of normalized E.164
 * numbers gives the RAG stats endpoint a real admin boundary without changing
 * the token contract or the Android client. It is resolved per request from the
 * user row, so removing a phone from the configuration takes effect on the
 * next request rather than at the next login.
 */
@Component
public class RagStatsAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Logger log = LoggerFactory.getLogger(RagStatsAuthorizationManager.class);

    private final UserRepository userRepository;
    private final Set<String> adminPhones;

    public RagStatsAuthorizationManager(UserRepository userRepository, SecurityProperties properties) {
        this.userRepository = userRepository;
        this.adminPhones = properties.adminPhones().stream()
                .map(phone -> {
                    try {
                        return PhoneNormalizer.toE164(phone);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(
                                "nexachat.security.admin-phones must contain valid E.164 numbers", e);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
        if (adminPhones.isEmpty()) {
            log.warn("No admin phones configured: /api/v1/rag/stats is denied to every caller");
        }
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !(auth.getPrincipal() instanceof JwtPrincipal principal)) {
            return new AuthorizationDecision(false);
        }
        boolean admin = userRepository.findById(principal.userId())
                .map(User::getPhone)
                .map(adminPhones::contains)
                .orElse(false);
        if (!admin) {
            log.warn("Denied operator telemetry to a non-admin caller");
        }
        return new AuthorizationDecision(admin);
    }
}

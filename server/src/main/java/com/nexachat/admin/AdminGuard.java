package com.nexachat.admin;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Role checks for the admin console, read from the SecurityContext's
 * authorities (which {@code JwtAuthFilter} loads fresh from {@code users.role}
 * on every request). {@code /api/v1/admin/**} already requires a staff role at
 * the filter-chain level; this guard layers the finer ADMIN-vs-MODERATOR
 * split on top for the destructive operations, and fails closed when the
 * context is missing or carries an unexpected principal.
 */
public final class AdminGuard {

    private AdminGuard() {
    }

    /** Authenticated staff member; {@code UNAUTHORIZED} when absent (fail closed). */
    public static JwtPrincipal requireStaff() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        if (role() == User.Role.USER) {
            throw ApiException.of(ApiErrorCode.FORBIDDEN, "Staff role required");
        }
        return principal;
    }

    /** Staff member holding the ADMIN role; MODERATOR and below get 403. */
    public static JwtPrincipal requireAdmin() {
        JwtPrincipal principal = requireStaff();
        if (role() != User.Role.ADMIN) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Admin role required");
        }
        return principal;
    }

    /** The caller's effective role, mapped from the {@code ROLE_*} authorities. */
    public static User.Role role() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return User.Role.USER;
        }
        User.Role best = User.Role.USER;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if ("ROLE_ADMIN".equals(name)) {
                return User.Role.ADMIN;
            }
            if ("ROLE_MODERATOR".equals(name)) {
                best = User.Role.MODERATOR;
            }
        }
        return best;
    }

    /** Convenience for audit rows: actor id + role after {@link #requireStaff()}. */
    public static UUID actorId() {
        return requireStaff().userId();
    }
}

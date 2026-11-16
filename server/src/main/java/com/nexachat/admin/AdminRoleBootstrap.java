package com.nexachat.admin;

import com.nexachat.auth.AccessTokenValidityCache;
import com.nexachat.common.PhoneNormalizer;
import com.nexachat.config.SecurityProperties;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bootstraps staff accounts from {@code nexachat.security.admin-phones}.
 *
 * <p>The phone allow-list is the operator escape hatch that predates the
 * role column: any existing or newly registered account whose phone is
 * configured here is promoted to {@code ADMIN}. Promotion is idempotent — it
 * only writes when the role differs — and it is one-directional by design:
 * removing a phone from the list never demotes an account (demotion is an
 * audited admin action). Startup promotion covers accounts that already
 * existed before this version shipped; {@link #promoteIfConfigured} runs on
 * every login/registration so a phone added later still works.
 */
@Component
public class AdminRoleBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleBootstrap.class);

    private final UserRepository userRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessTokenValidityCache validityCache;
    private final Set<String> adminPhones;

    public AdminRoleBootstrap(UserRepository userRepository,
                              org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                              SecurityProperties properties,
                              AccessTokenValidityCache validityCache) {
        this.userRepository = userRepository;
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.validityCache = validityCache;
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
    }

    /**
     * One-shot sweep at startup: promotes every live account whose phone is
     * configured. Runs after Flyway so {@code users.role} exists.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void promoteConfiguredAdminsOnStartup() {
        if (adminPhones.isEmpty()) {
            return;
        }
        int promoted = jdbc.update("""
                UPDATE users SET role = 'ADMIN'
                WHERE phone IN (:phones) AND role <> 'ADMIN' AND deleted_at IS NULL
                """, new MapSqlParameterSource("phones", adminPhones));
        if (promoted > 0) {
            log.info("Bootstrapped {} configured admin account(s) to ADMIN role", promoted);
        }
    }

    /**
     * Promotes this account inside the caller's transaction when its phone is
     * in the configured allow-list and it is not already ADMIN. Called on
     * every session issue so a newly configured phone takes effect at the
     * next login without waiting for a restart.
     */
    @Transactional
    public void promoteIfConfigured(User user) {
        String phone = user.getPhone();
        if (phone == null || !adminPhones.contains(phone) || user.getRole() == User.Role.ADMIN) {
            return;
        }
        user.changeRole(User.Role.ADMIN);
        userRepository.save(user);
        // The new role must reach the JWT hot path at once: a cached USER
        // validity would hide the admin routes until the entry expired.
        validityCache.evict(user.getId());
        log.info("Promoted a configured admin phone to ADMIN role at session issue");
    }
}

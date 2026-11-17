package com.nexachat.admin;

import com.nexachat.auth.AccessTokenValidityCache;
import com.nexachat.config.SecurityProperties;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Login-time admin promotion: the JWT filter reads roles through
 * {@link AccessTokenValidityCache}, so a promotion must drop the stale entry
 * at once or the fresh ADMIN stays locked out until the entry expires.
 */
class AdminRoleBootstrapTest {

    private static final String ADMIN_PHONE = "+84977123456";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccessTokenValidityCache validityCache = mock(AccessTokenValidityCache.class);

    @Test
    void promotingAConfiguredPhoneEvictsTheCachedValidity() {
        User user = user(ADMIN_PHONE);

        bootstrap(ADMIN_PHONE).promoteIfConfigured(user);

        assertThat(user.getRole()).isEqualTo(User.Role.ADMIN);
        verify(userRepository).save(user);
        verify(validityCache).evict(user.getId());
    }

    @Test
    void unconfiguredAndAlreadyAdminPhonesLeaveTheCacheAlone() {
        User other = user("+84977000001");
        User admin = user(ADMIN_PHONE);
        admin.changeRole(User.Role.ADMIN);
        AdminRoleBootstrap bootstrap = bootstrap(ADMIN_PHONE);

        bootstrap.promoteIfConfigured(other);
        bootstrap.promoteIfConfigured(admin);

        verify(validityCache, never()).evict(any());
    }

    @Test
    void aConfiguredVerifiedEmailPromotesAnAccountWithoutAPhone() {
        User user = User.createEmailAccount("Owner", "owner@example.com", "x".repeat(60),
                java.time.Instant.parse("2026-11-17T00:00:00Z"));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        bootstrapWithEmails("Owner@Example.com").promoteIfConfigured(user);

        assertThat(user.getRole()).isEqualTo(User.Role.ADMIN);
        verify(validityCache).evict(user.getId());
    }

    @Test
    void anUnverifiedEmailNeverPromotes() {
        User user = User.createEmailAccount("Owner", "owner@example.com", "x".repeat(60), null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        bootstrapWithEmails("owner@example.com").promoteIfConfigured(user);

        assertThat(user.getRole()).isNotEqualTo(User.Role.ADMIN);
        verify(validityCache, never()).evict(any());
    }

    private AdminRoleBootstrap bootstrapWithEmails(String... emails) {
        return new AdminRoleBootstrap(userRepository, new JdbcTemplate(),
                new SecurityProperties(List.of(), List.of(), List.of(), null, List.of(emails)),
                validityCache);
    }

    private AdminRoleBootstrap bootstrap(String... phones) {
        return new AdminRoleBootstrap(userRepository, new JdbcTemplate(),
                new SecurityProperties(List.of(), List.of(phones), List.of(), null),
                validityCache);
    }

    private static User user(String phone) {
        User user = User.create(phone, "x".repeat(60), "Candidate");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}

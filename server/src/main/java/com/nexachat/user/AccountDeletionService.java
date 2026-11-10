package com.nexachat.user;

import com.nexachat.attachment.StorageService;
import com.nexachat.auth.AccessTokenRevocationService;
import com.nexachat.auth.TokenService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.ContactRepository;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.GroupService;
import com.nexachat.notification.UserDeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Permanently deletes (tombstones) the authenticated account in one
 * transaction: revoke sessions, scrub PII, leave every group, and keep direct
 * conversations plus sent messages for the other members.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    private static final String FILES_PREFIX = "/api/v1/files/";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AccessTokenRevocationService accessTokenRevocationService;
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final ConversationMemberRepository memberRepository;
    private final GroupService groupService;
    private final StorageService storageService;
    private final ContactRepository contactRepository;
    private final Clock clock;
    private final AccountDeletionJdbcSupport jdbcSupport;

    public AccountDeletionService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  TokenService tokenService,
                                  AccessTokenRevocationService accessTokenRevocationService,
                                  UserDeviceTokenRepository deviceTokenRepository,
                                  ConversationMemberRepository memberRepository,
                                  GroupService groupService,
                                  StorageService storageService,
                                  ContactRepository contactRepository,
                                  Clock clock,
                                  @Autowired(required = false) AccountDeletionJdbcSupport jdbcSupport) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.accessTokenRevocationService = accessTokenRevocationService;
        this.deviceTokenRepository = deviceTokenRepository;
        this.memberRepository = memberRepository;
        this.groupService = groupService;
        this.storageService = storageService;
        this.contactRepository = contactRepository;
        this.clock = clock;
        this.jdbcSupport = jdbcSupport;
    }

    @Transactional
    public void deleteAccount(UUID userId, String password) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.of(ApiErrorCode.WRONG_PASSWORD, "Current password is incorrect");
        }

        leaveAllGroups(userId);
        contactRepository.deleteAllInvolvingUser(userId);
        deviceTokenRepository.deleteAllByUserId(userId);
        if (jdbcSupport != null) {
            jdbcSupport.scrubAiData(userId);
        }
        deleteAvatarFile(user);

        tokenService.revokeAllForUser(userId);
        accessTokenRevocationService.revoke(userId);

        user.markDeleted(Instant.now(clock));
        userRepository.save(user);
        log.info("Account deleted for user {}", userId);
    }

    private void leaveAllGroups(UUID userId) {
        List<UUID> groupIds = new ArrayList<>(memberRepository.findGroupConversationIdsForUser(userId));
        for (UUID conversationId : groupIds) {
            groupService.leave(userId, conversationId);
        }
    }

    private void deleteAvatarFile(User user) {
        String avatarUrl = user.getAvatarUrl();
        if (avatarUrl == null || !avatarUrl.startsWith(FILES_PREFIX)) {
            return;
        }
        String storageKey = avatarUrl.substring(FILES_PREFIX.length());
        storageService.deleteIfExists(storageKey);
    }
}

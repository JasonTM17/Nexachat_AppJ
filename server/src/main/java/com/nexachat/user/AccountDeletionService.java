package com.nexachat.user;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.ContactRepository;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.GroupService;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.auth.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
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
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final ConversationMemberRepository memberRepository;
    private final GroupService groupService;
    private final StorageService storageService;
    private final ContactRepository contactRepository;
    private final Clock clock;
    private final AccountDeletionJdbcSupport jdbcSupport;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountDeletionCoordination coordination;

    public AccountDeletionService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  TokenService tokenService,
                                  UserDeviceTokenRepository deviceTokenRepository,
                                  ConversationMemberRepository memberRepository,
                                  GroupService groupService,
                                  StorageService storageService,
                                  ContactRepository contactRepository,
                                  Clock clock,
                                  AccountDeletionJdbcSupport jdbcSupport,
                                  ApplicationEventPublisher eventPublisher,
                                  AccountDeletionCoordination coordination) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.deviceTokenRepository = deviceTokenRepository;
        this.memberRepository = memberRepository;
        this.groupService = groupService;
        this.storageService = storageService;
        this.contactRepository = contactRepository;
        this.clock = clock;
        this.jdbcSupport = jdbcSupport;
        this.eventPublisher = eventPublisher;
        this.coordination = coordination;
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

        deleteLockedAccount(user);
    }

    /** Uses the same tombstoning path after the recovery-email OTP is consumed. */
    @Transactional
    public void deleteAccountByVerifiedRecovery(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.INVALID_OR_EXPIRED_CODE,
                        "Code is invalid or expired"));
        if (user.getDeletedAt() != null || !user.hasVerifiedEmail()) {
            throw ApiException.of(ApiErrorCode.INVALID_OR_EXPIRED_CODE, "Code is invalid or expired");
        }
        deleteLockedAccount(user);
    }

    private void deleteLockedAccount(User user) {
        UUID userId = user.getId();

        String avatarStorageKey = avatarStorageKey(user.getAvatarUrl());
        List<String> unboundKeys = new ArrayList<>(jdbcSupport.findUnboundAttachmentStorageKeys(userId));

        leaveAllGroups(userId);
        contactRepository.deleteAllInvolvingUser(userId);
        deviceTokenRepository.deleteAllByUserId(userId);
        jdbcSupport.scrubAiData(userId);
        jdbcSupport.deleteGroupJoinRequests(userId);
        jdbcSupport.deleteLabels(userId);
        jdbcSupport.deleteGoogleIdentityData(userId);
        jdbcSupport.deleteEmailOtps(userId);
        jdbcSupport.deleteUnboundAttachments(userId);

        tokenService.revokeAllForUser(userId);

        user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        coordination.beforeTombstone(userId);

        Instant deletedAt = Instant.now(clock);
        Instant accessCutoff = deletedAt.truncatedTo(ChronoUnit.SECONDS);
        user.markDeleted(deletedAt);
        user.invalidateAccessTokens(accessCutoff);
        userRepository.saveAndFlush(user);

        deleteStorageAfterCommit(avatarStorageKey, unboundKeys);
        eventPublisher.publishEvent(new AccessTokensRevokedEvent(userId));
        log.info("Account deleted for user {}", userId);
    }

    private void leaveAllGroups(UUID userId) {
        List<UUID> groupIds = new ArrayList<>(memberRepository.findGroupConversationIdsForUser(userId));
        Collections.sort(groupIds);
        for (UUID conversationId : groupIds) {
            groupService.leaveForAccountDeletion(userId, conversationId);
        }
    }

    private static String avatarStorageKey(String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(FILES_PREFIX)) {
            return null;
        }
        return avatarUrl.substring(FILES_PREFIX.length());
    }

    private void deleteStorageAfterCommit(String avatarKey, List<String> unboundKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteStorageKeys(avatarKey, unboundKeys);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteStorageKeys(avatarKey, unboundKeys);
            }
        });
    }

    private void deleteStorageKeys(String avatarKey, List<String> unboundKeys) {
        if (avatarKey != null) {
            storageService.deleteIfExists(avatarKey);
        }
        for (String key : unboundKeys) {
            storageService.deleteIfExists(key);
        }
    }
}

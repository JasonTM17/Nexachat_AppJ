package com.nexachat.user;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.attachment.AvatarReferenceCoordinator;
import com.nexachat.call.CallRecordRepository;
import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.ContactRepository;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.GroupService;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.auth.TokenService;
import com.nexachat.recovery.EmailOtpService;
import com.nexachat.recovery.OtpPurpose;
import com.nexachat.ws.StompRevocationFence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
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
    private final CallRecordRepository callRecordRepository;
    private final GroupService groupService;
    private final StorageService storageService;
    private final AvatarReferenceCoordinator avatarReferenceCoordinator;
    private final ContactRepository contactRepository;
    private final Clock clock;
    private final AccountDeletionJdbcSupport jdbcSupport;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountDeletionCoordination coordination;
    private final StompRevocationFence revocationFence;
    private final EmailOtpService emailOtpService;

    public AccountDeletionService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  TokenService tokenService,
                                  UserDeviceTokenRepository deviceTokenRepository,
                                  ConversationMemberRepository memberRepository,
                                  @Lazy CallRecordRepository callRecordRepository,
                                  GroupService groupService,
                                  StorageService storageService,
                                  AvatarReferenceCoordinator avatarReferenceCoordinator,
                                  ContactRepository contactRepository,
                                  Clock clock,
                                  AccountDeletionJdbcSupport jdbcSupport,
                                  ApplicationEventPublisher eventPublisher,
                                  AccountDeletionCoordination coordination,
                                  StompRevocationFence revocationFence,
                                  EmailOtpService emailOtpService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.deviceTokenRepository = deviceTokenRepository;
        this.memberRepository = memberRepository;
        this.callRecordRepository = callRecordRepository;
        this.groupService = groupService;
        this.storageService = storageService;
        this.avatarReferenceCoordinator = avatarReferenceCoordinator;
        this.contactRepository = contactRepository;
        this.clock = clock;
        this.jdbcSupport = jdbcSupport;
        this.eventPublisher = eventPublisher;
        this.coordination = coordination;
        this.revocationFence = revocationFence;
        this.emailOtpService = emailOtpService;
    }

    @Transactional
    public void deleteAccount(UUID userId, String password) {
        deleteAccount(userId, password, null);
    }

    /**
     * Deletes the account. A password hash still requires {@code password};
     * {@code code} is ignored in that case. An account with no hash requires a
     * live DELETE_ACCOUNT code for its own verified email.
     */
    @Transactional
    public void deleteAccount(UUID userId, String password, String code) {
        revocationFence.holdWriteUntilTransactionCompletion(userId);
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        if (user.hasPassword()) {
            if (password == null || password.isBlank()
                    || !passwordEncoder.matches(password, user.getPasswordHash())) {
                throw ApiException.of(ApiErrorCode.WRONG_PASSWORD, "Current password is incorrect");
            }
        } else if (!emailOtpService.consume(user, OtpPurpose.DELETE_ACCOUNT, code)) {
            throw ApiException.of(ApiErrorCode.INVALID_OR_EXPIRED_CODE, "Code is invalid or expired");
        }

        deleteLockedAccount(user);
    }

    /** Uses the same tombstoning path after the recovery-email OTP is consumed. */
    @Transactional
    public void deleteAccountByVerifiedRecovery(UUID userId) {
        revocationFence.holdWriteUntilTransactionCompletion(userId);
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
        List<UUID> firebaseConversationIds = memberRepository.findConversationIdsByUserId(userId).stream()
                .distinct().sorted().toList();
        List<UUID> firebaseDirectPeerIds = memberRepository.findDirectPeerUserIdsByUserId(userId);
        List<UUID> firebaseCallIds = callRecordRepository.findCallIdsByParticipantId(userId).stream()
                .distinct().sorted().toList();

        String avatarStorageKey = avatarStorageKey(user.getAvatarUrl());
        // Delete the account's unbound upload rows before any group
        // conversation row is locked. A send locks its attachment rows first
        // and only then references the sender row and advances the
        // conversation; deleting these rows later would invert that order and
        // deadlock. SKIP LOCKED makes an in-flight send the winner of its own
        // row instead of making deletion wait for it, and RETURNING keeps the
        // post-commit file cleanup limited to files whose rows were removed.
        List<String> unboundKeys = jdbcSupport.deleteUnboundAttachments(userId);

        // Scrub caches while group memberships still identify every affected
        // conversation. leaveAllGroups removes those lookup rows.
        jdbcSupport.scrubAiData(userId);
        leaveAllGroups(userId);
        contactRepository.deleteAllInvolvingUser(userId);
        deviceTokenRepository.deleteAllByUserId(userId);
        jdbcSupport.deleteGroupJoinRequests(userId);
        jdbcSupport.deleteLabels(userId);
        jdbcSupport.deleteGoogleIdentityData(userId);
        jdbcSupport.deleteEmailOtps(userId);

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
        eventPublisher.publishEvent(new UserAccountDeletedEvent(
                userId, firebaseConversationIds, firebaseDirectPeerIds, List.of(), firebaseCallIds));
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
            avatarReferenceCoordinator.deleteIfUnreferenced(avatarKey);
        }
        for (String key : unboundKeys) {
            storageService.deleteIfExists(key);
        }
    }
}

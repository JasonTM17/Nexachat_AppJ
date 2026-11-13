package com.nexachat.invite;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.GroupService;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.invite.dto.InviteLinkResponse;
import com.nexachat.invite.dto.InvitePreviewResponse;
import com.nexachat.invite.dto.JoinRequestResponse;
import com.nexachat.invite.dto.JoinResponse;
import com.nexachat.invite.dto.PatchInviteLinkRequest;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import jakarta.persistence.EntityManager;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

    @Mock
    private GroupInviteLinkRepository linkRepository;
    @Mock
    private GroupJoinRequestRepository joinRequestRepository;
    @Mock
    private GroupInviteBanRepository banRepository;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GroupService groupService;
    @Mock
    private InviteRateLimiter rateLimiter;
    @Mock
    private EntityManager entityManager;

    private InviteTokenGenerator tokenGenerator;
    private InviteService inviteService;

    private final UUID adminId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    private User adminUser;
    private User memberUser;
    private User strangerUser;
    private Conversation groupConversation;
    private Conversation directConversation;

    @BeforeEach
    void setUp() {
        tokenGenerator = new InviteTokenGenerator();
        adminUser = userWithId(adminId, "+84900000001", "Admin");
        memberUser = userWithId(memberId, "+84900000002", "Member");
        strangerUser = userWithId(strangerId, "+84900000003", "Stranger");

        groupConversation = Conversation.group(adminUser, "Test Group");
        ReflectionTestUtils.setField(groupConversation, "id", conversationId);

        directConversation = Conversation.direct(adminUser, "direct_pair_key");
        ReflectionTestUtils.setField(directConversation, "id", UUID.randomUUID());

        inviteService = new InviteService(
                linkRepository, joinRequestRepository, banRepository,
                conversationRepository, memberRepository, userRepository,
                groupService, tokenGenerator, rateLimiter, entityManager, 5);

        lenient().when(joinRequestRepository.findByIdForUpdate(any()))
                .thenAnswer(invocation -> joinRequestRepository.findById(invocation.getArgument(0)));
        lenient().when(memberRepository.findCurrentRole(conversationId, adminId))
                .thenReturn(Optional.of(ConversationMember.Role.ADMIN));
        lenient().when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(groupConversation));
        lenient().when(userRepository.findActiveUsersByIdsForUpdate(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<UUID> ids = invocation.getArgument(0);
            return ids.stream().map(this::userForId).filter(java.util.Objects::nonNull).toList();
        });
    }

    private User userForId(UUID id) {
        if (adminId.equals(id)) {
            return adminUser;
        }
        if (memberId.equals(id)) {
            return memberUser;
        }
        if (strangerId.equals(id)) {
            return strangerUser;
        }
        return null;
    }

    private User userWithId(UUID id, String phone, String name) {
        User u = User.create(phone, "hash", name);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private ConversationMember memberRow(Conversation c, User u, ConversationMember.Role role) {
        return ConversationMember.join(c, u, role);
    }

    // =========================================================================
    // AuthZ Matrix & Validation
    // =========================================================================

    @Test
    void createLink_byNonMember_failsNotMember() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, strangerId)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> inviteService.createOrRotateInviteLink(strangerId, conversationId, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void createLink_byMemberNonAdmin_failsNotAdmin() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, memberId))
                .thenReturn(Optional.of(memberRow(groupConversation, memberUser, ConversationMember.Role.MEMBER)));

        ApiException ex = catchThrowableOfType(
                () -> inviteService.createOrRotateInviteLink(memberId, conversationId, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_ADMIN);
    }

    @Test
    void createLink_onDirectConversation_failsNotAGroup() {
        UUID directId = directConversation.getId();
        when(conversationRepository.findById(directId)).thenReturn(Optional.of(directConversation));

        ApiException ex = catchThrowableOfType(
                () -> inviteService.createOrRotateInviteLink(adminId, directId, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_A_GROUP);
    }

    @Test
    void createLink_expiryInPast_failsValidation() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));

        CreateInviteLinkRequest request = new CreateInviteLinkRequest(
                Instant.now().minus(Duration.ofHours(1)), null, false);

        ApiException ex = catchThrowableOfType(
                () -> inviteService.createOrRotateInviteLink(adminId, conversationId, request),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void createLink_expiryExceeds30Days_failsValidation() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));

        CreateInviteLinkRequest request = new CreateInviteLinkRequest(
                Instant.now().plus(Duration.ofDays(31)), null, false);

        ApiException ex = catchThrowableOfType(
                () -> inviteService.createOrRotateInviteLink(adminId, conversationId, request),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void createLink_negativeMaxUses_failsValidation() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));

        CreateInviteLinkRequest request = new CreateInviteLinkRequest(null, 0, false);

        ApiException ex = catchThrowableOfType(
                () -> inviteService.createOrRotateInviteLink(adminId, conversationId, request),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void patchLink_nullRequiresApproval_failsValidation() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));

        ApiException ex = catchThrowableOfType(
                () -> inviteService.patchInviteLink(adminId, conversationId, new PatchInviteLinkRequest(null)),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    // =========================================================================
    // Create, Rotate, Revoke & Metadata
    // =========================================================================

    @Test
    void createLink_success_generatesTokenAndStoresHashed() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));
        when(linkRepository.findAllByConversation_IdAndRevokedAtIsNull(conversationId)).thenReturn(List.of());
        when(linkRepository.save(any(GroupInviteLink.class))).thenAnswer(i -> {
            GroupInviteLink link = i.getArgument(0);
            ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
            return link;
        });

        CreateInviteLinkRequest request = new CreateInviteLinkRequest(
                Instant.now().plus(Duration.ofDays(7)), 10, true);

        InviteLinkResponse response = inviteService.createOrRotateInviteLink(adminId, conversationId, request);

        assertThat(response.token()).isNotBlank();
        assertThat(response.requiresApproval()).isTrue();
        assertThat(response.maxUses()).isEqualTo(10);
        assertThat(response.useCount()).isEqualTo(0);

        ArgumentCaptor<GroupInviteLink> captor = ArgumentCaptor.forClass(GroupInviteLink.class);
        verify(linkRepository).save(captor.capture());
        GroupInviteLink saved = captor.getValue();
        // Plaintext token is never stored in DB
        assertThat(saved.getTokenHash()).isNotEqualTo(response.token());
        assertThat(saved.getTokenHash()).isEqualTo(tokenGenerator.hashToken(response.token()));
    }

    @Test
    void createLink_existingActiveLink_rotatesByRevokingOld() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));

        GroupInviteLink existing = new GroupInviteLink(
                groupConversation, adminUser, "oldhash", false, null, null);
        when(linkRepository.findAllByConversation_IdAndRevokedAtIsNull(conversationId))
                .thenReturn(List.of(existing));
        when(linkRepository.save(any(GroupInviteLink.class))).thenAnswer(i -> i.getArgument(0));

        inviteService.createOrRotateInviteLink(adminId, conversationId, null);

        assertThat(existing.isRevoked()).isTrue();
        assertThat(existing.getRevokedBy()).isEqualTo(adminUser);
        verify(linkRepository).saveAllAndFlush(List.of(existing));
    }

    @Test
    void revokeLink_success_marksRevoked() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));

        GroupInviteLink active = new GroupInviteLink(
                groupConversation, adminUser, "hash123", false, null, null);
        when(linkRepository.findByConversation_IdAndRevokedAtIsNull(conversationId)).thenReturn(Optional.of(active));

        inviteService.revokeInviteLink(adminId, conversationId);

        assertThat(active.isRevoked()).isTrue();
        verify(linkRepository).save(active);
    }

    @Test
    void getActiveLink_returnsMetadataWithoutPlaintextToken() {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));

        GroupInviteLink active = new GroupInviteLink(
                groupConversation, adminUser, "hash123", true, null, 5);
        ReflectionTestUtils.setField(active, "id", UUID.randomUUID());
        when(linkRepository.findByConversation_IdAndRevokedAtIsNull(conversationId)).thenReturn(Optional.of(active));

        InviteLinkResponse response = inviteService.getActiveInviteLink(adminId, conversationId);

        assertThat(response.id()).isEqualTo(active.getId());
        assertThat(response.token()).isNull(); // Plaintext token cannot be recovered from hash
        assertThat(response.requiresApproval()).isTrue();
        assertThat(response.maxUses()).isEqualTo(5);
    }

    // =========================================================================
    // Safe Preview & Uniform Errors
    // =========================================================================

    @Test
    void resolvePreview_success_returnsTitleAvatarCountNeverMemberList() {
        String rawToken = "valid_preview_token";
        String hash = tokenGenerator.hashToken(rawToken);

        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, hash, true, null, null);
        when(linkRepository.findByTokenHash(hash)).thenReturn(Optional.of(link));
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(3L);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, strangerId)).thenReturn(false);

        InvitePreviewResponse preview = inviteService.resolvePreview(strangerId, rawToken, "127.0.0.1");

        assertThat(preview.conversationId()).isEqualTo(conversationId);
        assertThat(preview.title()).isEqualTo("Test Group");
        assertThat(preview.memberCount()).isEqualTo(3);
        assertThat(preview.requiresApproval()).isTrue();
        assertThat(preview.isMember()).isFalse();
        verify(rateLimiter).checkResolve(strangerId, "127.0.0.1");
    }

    @Test
    void resolvePreview_uniformNotFound_forInvalidTokens() {
        // 1. Non-existent token
        String unknownToken = "unknown_token";
        String unknownHash = tokenGenerator.hashToken(unknownToken);
        when(linkRepository.findByTokenHash(unknownHash)).thenReturn(Optional.empty());

        ApiException ex1 = catchThrowableOfType(
                () -> inviteService.resolvePreview(strangerId, unknownToken, "127.0.0.1"),
                ApiException.class);
        assertThat(ex1.getCode()).isEqualTo(ApiErrorCode.NOT_FOUND);

        // 2. Expired token
        String expiredToken = "expired_token";
        String expiredHash = tokenGenerator.hashToken(expiredToken);
        GroupInviteLink expired = new GroupInviteLink(
                groupConversation, adminUser, expiredHash, false, Instant.now().minus(Duration.ofMinutes(1)), null);
        when(linkRepository.findByTokenHash(expiredHash)).thenReturn(Optional.of(expired));

        ApiException ex2 = catchThrowableOfType(
                () -> inviteService.resolvePreview(strangerId, expiredToken, "127.0.0.1"),
                ApiException.class);
        assertThat(ex2.getCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(ex2.getMessage()).isEqualTo(ex1.getMessage()); // Uniform message

        // 3. Revoked token
        String revokedToken = "revoked_token";
        String revokedHash = tokenGenerator.hashToken(revokedToken);
        GroupInviteLink revoked = new GroupInviteLink(
                groupConversation, adminUser, revokedHash, false, null, null);
        revoked.revoke(adminUser, Instant.now());
        when(linkRepository.findByTokenHash(revokedHash)).thenReturn(Optional.of(revoked));

        ApiException ex3 = catchThrowableOfType(
                () -> inviteService.resolvePreview(strangerId, revokedToken, "127.0.0.1"),
                ApiException.class);
        assertThat(ex3.getCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(ex3.getMessage()).isEqualTo(ex1.getMessage());

        // 4. Used-up token
        String usedToken = "used_token";
        String usedHash = tokenGenerator.hashToken(usedToken);
        GroupInviteLink usedUp = new GroupInviteLink(
                groupConversation, adminUser, usedHash, false, null, 1);
        usedUp.incrementUseCount();
        when(linkRepository.findByTokenHash(usedHash)).thenReturn(Optional.of(usedUp));

        ApiException ex4 = catchThrowableOfType(
                () -> inviteService.resolvePreview(strangerId, usedToken, "127.0.0.1"),
                ApiException.class);
        assertThat(ex4.getCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(ex4.getMessage()).isEqualTo(ex1.getMessage());
    }

    // =========================================================================
    // Join Flow (Direct vs Approval) & Member Cap
    // =========================================================================

    @Test
    void join_withoutApproval_directMemberAdditionAndUseCountIncremented() {
        String token = "direct_join_token";
        String hash = tokenGenerator.hashToken(token);

        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, hash, false, null, 10);
        UUID linkId = UUID.randomUUID();
        ReflectionTestUtils.setField(link, "id", linkId);

        when(linkRepository.findByTokenHash(hash)).thenReturn(Optional.of(link));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, strangerId)).thenReturn(false);
        when(banRepository.isBannedFromLink(conversationId, strangerId, linkId)).thenReturn(false);
        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(2L);
        when(linkRepository.findByIdForUpdate(linkId)).thenReturn(Optional.of(link));
        when(linkRepository.tryConsumeUse(linkId)).thenReturn(1);

        JoinResponse response = inviteService.join(strangerId, token, "127.0.0.1");

        assertThat(response.status()).isEqualTo("JOINED");
        assertThat(response.conversationId()).isEqualTo(conversationId);
        assertThat(response.requestId()).isNull();
        verify(linkRepository).tryConsumeUse(linkId);
        verify(groupService).addMemberFromInvite(strangerId, conversationId, null);
    }

    @Test
    void join_alreadyMember_idempotentSuccess() {
        String token = "token123";
        String hash = tokenGenerator.hashToken(token);

        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, hash, false, null, null);
        when(linkRepository.findByTokenHash(hash)).thenReturn(Optional.of(link));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, memberId)).thenReturn(true);

        JoinResponse response = inviteService.join(memberId, token, "127.0.0.1");

        assertThat(response.status()).isEqualTo("ALREADY_MEMBER");
        verify(groupService, never()).addMemberFromInvite(any(), any(), any());
    }

    @Test
    void join_removedByAdmin_forbiddenViaSameLink() {
        String token = "banned_user_token";
        String hash = tokenGenerator.hashToken(token);

        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, hash, false, null, null);
        UUID linkId = UUID.randomUUID();
        ReflectionTestUtils.setField(link, "id", linkId);

        when(linkRepository.findByTokenHash(hash)).thenReturn(Optional.of(link));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, strangerId)).thenReturn(false);
        when(banRepository.isBannedFromLink(conversationId, strangerId, linkId)).thenReturn(true);

        ApiException ex = catchThrowableOfType(
                () -> inviteService.join(strangerId, token, "127.0.0.1"),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FORBIDDEN);
        verify(groupService, never()).addMemberFromInvite(any(), any(), any());
    }

    @Test
    void join_memberCapReached_failsValidation() {
        String token = "cap_token";
        String hash = tokenGenerator.hashToken(token);

        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, hash, false, null, null);
        UUID linkId = UUID.randomUUID();
        ReflectionTestUtils.setField(link, "id", linkId);

        when(linkRepository.findByTokenHash(hash)).thenReturn(Optional.of(link));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, strangerId)).thenReturn(false);
        when(banRepository.isBannedFromLink(conversationId, strangerId, linkId)).thenReturn(false);
        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(groupConversation));
        // maxMembers = 5 configured in setUp()
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(5L);

        ApiException ex = catchThrowableOfType(
                () -> inviteService.join(strangerId, token, "127.0.0.1"),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(ex.getDetails()).containsEntry("limit", 5);
    }

    @Test
    void join_requiresApproval_createsPendingRequest() {
        String token = "approval_token";
        String hash = tokenGenerator.hashToken(token);

        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, hash, true, null, null);
        UUID linkId = UUID.randomUUID();
        ReflectionTestUtils.setField(link, "id", linkId);

        when(linkRepository.findByTokenHash(hash)).thenReturn(Optional.of(link));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, strangerId)).thenReturn(false);
        when(banRepository.isBannedFromLink(conversationId, strangerId, linkId)).thenReturn(false);
        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(2L);
        when(linkRepository.findByIdForUpdate(linkId)).thenReturn(Optional.of(link));
        when(joinRequestRepository.findByConversation_IdAndUser_IdAndStatus(
                conversationId, strangerId, GroupJoinRequest.Status.PENDING)).thenReturn(Optional.empty());
        when(joinRequestRepository.findFirstByConversation_IdAndUser_IdOrderByCreatedAtDesc(
                conversationId, strangerId)).thenReturn(Optional.empty());
        UUID reqId = UUID.randomUUID();
        when(joinRequestRepository.save(any(GroupJoinRequest.class))).thenAnswer(i -> {
            GroupJoinRequest r = i.getArgument(0);
            ReflectionTestUtils.setField(r, "id", reqId);
            return r;
        });

        JoinResponse response = inviteService.join(strangerId, token, "127.0.0.1");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.requestId()).isEqualTo(reqId);
        verify(groupService, never()).addMemberFromInvite(any(), any(), any());
    }

    @Test
    void join_alreadyPending_returnsExistingPendingIdempotently() {
        String token = "approval_token";
        String hash = tokenGenerator.hashToken(token);

        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, hash, true, null, null);
        UUID linkId = UUID.randomUUID();
        ReflectionTestUtils.setField(link, "id", linkId);

        when(linkRepository.findByTokenHash(hash)).thenReturn(Optional.of(link));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, strangerId)).thenReturn(false);
        when(banRepository.isBannedFromLink(conversationId, strangerId, linkId)).thenReturn(false);
        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(2L);
        when(linkRepository.findByIdForUpdate(linkId)).thenReturn(Optional.of(link));

        UUID existingReqId = UUID.randomUUID();
        GroupJoinRequest existingReq = new GroupJoinRequest(groupConversation, link, strangerUser);
        ReflectionTestUtils.setField(existingReq, "id", existingReqId);
        when(joinRequestRepository.findByConversation_IdAndUser_IdAndStatus(
                conversationId, strangerId, GroupJoinRequest.Status.PENDING)).thenReturn(Optional.of(existingReq));

        JoinResponse response = inviteService.join(strangerId, token, "127.0.0.1");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.requestId()).isEqualTo(existingReqId);
        verify(joinRequestRepository, never()).save(any());
    }

    @Test
    void join_previouslyRejected_failsForbidden() {
        String token = "approval_token";
        String hash = tokenGenerator.hashToken(token);

        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, hash, true, null, null);
        UUID linkId = UUID.randomUUID();
        ReflectionTestUtils.setField(link, "id", linkId);

        when(linkRepository.findByTokenHash(hash)).thenReturn(Optional.of(link));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, strangerId)).thenReturn(false);
        when(banRepository.isBannedFromLink(conversationId, strangerId, linkId)).thenReturn(false);
        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(2L);
        when(linkRepository.findByIdForUpdate(linkId)).thenReturn(Optional.of(link));
        when(joinRequestRepository.findByConversation_IdAndUser_IdAndStatus(
                conversationId, strangerId, GroupJoinRequest.Status.PENDING)).thenReturn(Optional.empty());

        GroupJoinRequest rejectedReq = new GroupJoinRequest(groupConversation, link, strangerUser);
        rejectedReq.reject(adminUser, Instant.now());
        when(joinRequestRepository.findFirstByConversation_IdAndUser_IdOrderByCreatedAtDesc(
                conversationId, strangerId)).thenReturn(Optional.of(rejectedReq));

        ApiException ex = catchThrowableOfType(
                () -> inviteService.join(strangerId, token, "127.0.0.1"),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    // =========================================================================
    // Admin Approval & Rejection Flow
    // =========================================================================

    @Test
    void approveJoinRequest_success_addsMemberAndUpdatesStatus() {
        UUID reqId = UUID.randomUUID();
        GroupInviteLink link = new GroupInviteLink(
                groupConversation, adminUser, "hash", true, null, 10);
        UUID linkId = UUID.randomUUID();
        ReflectionTestUtils.setField(link, "id", linkId);

        GroupJoinRequest request = new GroupJoinRequest(groupConversation, link, strangerUser);
        ReflectionTestUtils.setField(request, "id", reqId);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));
        when(joinRequestRepository.findById(reqId)).thenReturn(Optional.of(request));
        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.countByConversation_Id(conversationId)).thenReturn(2L);
        when(linkRepository.findByIdForUpdate(linkId)).thenReturn(Optional.of(link));
        when(linkRepository.tryConsumeUse(linkId)).thenReturn(1);
        when(joinRequestRepository.save(request)).thenReturn(request);

        JoinRequestResponse response = inviteService.approveJoinRequest(adminId, conversationId, reqId);

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.reviewedBy()).isEqualTo(adminId);
        verify(linkRepository).tryConsumeUse(linkId);
        verify(groupService).addMemberFromInvite(strangerId, conversationId, adminId);
    }

    @Test
    void rejectJoinRequest_success_updatesStatus() {
        UUID reqId = UUID.randomUUID();
        GroupJoinRequest request = new GroupJoinRequest(groupConversation, null, strangerUser);
        ReflectionTestUtils.setField(request, "id", reqId);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));
        when(joinRequestRepository.findById(reqId)).thenReturn(Optional.of(request));
        when(joinRequestRepository.save(request)).thenReturn(request);

        JoinRequestResponse response = inviteService.rejectJoinRequest(adminId, conversationId, reqId);

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.reviewedBy()).isEqualTo(adminId);
        verify(groupService, never()).addMemberFromInvite(any(), any(), any());
    }

    @Test
    void approveJoinRequest_alreadyApproved_idempotent() {
        UUID reqId = UUID.randomUUID();
        GroupJoinRequest request = new GroupJoinRequest(groupConversation, null, strangerUser);
        ReflectionTestUtils.setField(request, "id", reqId);
        request.approve(adminUser, Instant.now());

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));
        when(joinRequestRepository.findById(reqId)).thenReturn(Optional.of(request));

        JoinRequestResponse response = inviteService.approveJoinRequest(adminId, conversationId, reqId);

        assertThat(response.status()).isEqualTo("APPROVED");
        verify(groupService, never()).addMemberFromInvite(any(), any(), any());
    }

    @Test
    void rejectJoinRequest_alreadyRejected_idempotent() {
        UUID reqId = UUID.randomUUID();
        GroupJoinRequest request = new GroupJoinRequest(groupConversation, null, strangerUser);
        ReflectionTestUtils.setField(request, "id", reqId);
        request.reject(adminUser, Instant.now());

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));
        when(joinRequestRepository.findById(reqId)).thenReturn(Optional.of(request));

        JoinRequestResponse response = inviteService.rejectJoinRequest(adminId, conversationId, reqId);

        assertThat(response.status()).isEqualTo("REJECTED");
    }

    @Test
    void approveJoinRequest_onRejectedRequest_failsValidation() {
        UUID reqId = UUID.randomUUID();
        GroupJoinRequest request = new GroupJoinRequest(groupConversation, null, strangerUser);
        ReflectionTestUtils.setField(request, "id", reqId);
        request.reject(adminUser, Instant.now());

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(groupConversation));
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, adminId))
                .thenReturn(Optional.of(memberRow(groupConversation, adminUser, ConversationMember.Role.ADMIN)));
        when(joinRequestRepository.findById(reqId)).thenReturn(Optional.of(request));

        ApiException ex = catchThrowableOfType(
                () -> inviteService.approveJoinRequest(adminId, conversationId, reqId),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }
}

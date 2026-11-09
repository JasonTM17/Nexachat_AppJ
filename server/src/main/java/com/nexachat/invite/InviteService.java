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
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core business service for group invite links, safe previews, approval flows,
 * banned member enforcement, and member cap enforcement.
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    private static final Duration MAX_EXPIRY = Duration.ofDays(30);
    private static final int DEFAULT_JOIN_REQUEST_LIST_LIMIT = 100;
    private static final int MAX_JOIN_REQUEST_LIST_LIMIT = 100;

    private final GroupInviteLinkRepository linkRepository;
    private final GroupJoinRequestRepository joinRequestRepository;
    private final GroupInviteBanRepository banRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final GroupService groupService;
    private final InviteTokenGenerator tokenGenerator;
    private final InviteRateLimiter rateLimiter;
    private final EntityManager entityManager;
    private final int maxMembers;

    public InviteService(
            GroupInviteLinkRepository linkRepository,
            GroupJoinRequestRepository joinRequestRepository,
            GroupInviteBanRepository banRepository,
            ConversationRepository conversationRepository,
            ConversationMemberRepository memberRepository,
            UserRepository userRepository,
            GroupService groupService,
            InviteTokenGenerator tokenGenerator,
            InviteRateLimiter rateLimiter,
            EntityManager entityManager,
            @Value("${nexachat.group.max-members:100}") int maxMembers) {
        this.linkRepository = linkRepository;
        this.joinRequestRepository = joinRequestRepository;
        this.banRepository = banRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.groupService = groupService;
        this.tokenGenerator = tokenGenerator;
        this.rateLimiter = rateLimiter;
        this.entityManager = entityManager;
        this.maxMembers = Math.max(2, maxMembers);
    }

    /**
     * Creates or rotates an invite link for a group (ADMIN only).
     * Any existing active link for this conversation is revoked.
     */
    @Transactional
    public InviteLinkResponse createOrRotateInviteLink(
            UUID callerId, UUID conversationId, CreateInviteLinkRequest request) {
        Conversation conversation = requireGroup(conversationId);
        ConversationMember callerMember = requireMember(conversationId, callerId);
        requireAdmin(callerMember);

        conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));

        Instant now = Instant.now();
        Instant expiresAt = null;
        Integer maxUses = null;
        boolean requiresApproval = false;

        if (request != null) {
            if (request.expiresAt() != null) {
                if (!request.expiresAt().isAfter(now)) {
                    throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "expiresAt must be in the future");
                }
                if (request.expiresAt().isAfter(now.plus(MAX_EXPIRY))) {
                    throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "expiresAt cannot exceed 30 days");
                }
                expiresAt = request.expiresAt();
            }
            if (request.maxUses() != null) {
                if (request.maxUses() < 1) {
                    throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "maxUses must be at least 1");
                }
                maxUses = request.maxUses();
            }
            if (Boolean.TRUE.equals(request.requiresApproval())) {
                requiresApproval = true;
            }
        }

        // Revoke any existing active links (link rotation)
        List<GroupInviteLink> existing = linkRepository.findAllByConversation_IdAndRevokedAtIsNull(conversationId);
        for (GroupInviteLink oldLink : existing) {
            oldLink.revoke(callerMember.getUser(), now);
        }
        if (!existing.isEmpty()) {
            linkRepository.saveAllAndFlush(existing);
        }

        String rawToken = tokenGenerator.generateToken();
        String tokenHash = tokenGenerator.hashToken(rawToken);

        GroupInviteLink link = new GroupInviteLink(
                conversation, callerMember.getUser(), tokenHash,
                requiresApproval, expiresAt, maxUses);
        try {
            link = linkRepository.save(link);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Invite link was rotated concurrently; retry");
        }

        log.info("Invite link created/rotated for conversation {} by {}", conversationId, callerId);
        return toInviteLinkResponse(link, rawToken);
    }

    /**
     * Retrieves the current active invite link metadata for a group (ADMIN only).
     * Token plaintext is never returned here because it is stored hashed.
     */
    @Transactional(readOnly = true)
    public InviteLinkResponse getActiveInviteLink(UUID callerId, UUID conversationId) {
        requireGroup(conversationId);
        ConversationMember callerMember = requireMember(conversationId, callerId);
        requireAdmin(callerMember);

        GroupInviteLink link = linkRepository.findByConversation_IdAndRevokedAtIsNull(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "No active invite link found"));

        if (!link.isValid(Instant.now())) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "No active invite link found");
        }

        return toInviteLinkResponse(link, null);
    }

    /**
     * Updates an active invite link (e.g. toggling requiresApproval) (ADMIN only).
     */
    @Transactional
    public InviteLinkResponse patchInviteLink(
            UUID callerId, UUID conversationId, PatchInviteLinkRequest request) {
        requireGroup(conversationId);
        ConversationMember callerMember = requireMember(conversationId, callerId);
        requireAdmin(callerMember);

        if (request == null || request.requiresApproval() == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "requiresApproval is required");
        }

        GroupInviteLink link = linkRepository.findByConversation_IdAndRevokedAtIsNull(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "No active invite link found"));

        if (!link.isValid(Instant.now())) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "No active invite link found");
        }

        link.setRequiresApproval(request.requiresApproval());
        link = linkRepository.save(link);

        log.info("Invite link patched for conversation {} (requiresApproval={})",
                conversationId, request.requiresApproval());
        return toInviteLinkResponse(link, null);
    }

    /**
     * Revokes the active invite link for a group (ADMIN only).
     */
    @Transactional
    public void revokeInviteLink(UUID callerId, UUID conversationId) {
        requireGroup(conversationId);
        ConversationMember callerMember = requireMember(conversationId, callerId);
        requireAdmin(callerMember);

        Optional<GroupInviteLink> linkOpt = linkRepository.findByConversation_IdAndRevokedAtIsNull(conversationId);
        if (linkOpt.isPresent()) {
            GroupInviteLink link = linkOpt.get();
            link.revoke(callerMember.getUser(), Instant.now());
            linkRepository.save(link);
            log.info("Invite link revoked for conversation {} by {}", conversationId, callerId);
        }
    }

    /**
     * Resolves an invite link to a safe preview (group title, avatar, member count only).
     * Never returns member lists.
     * Uniform 404 is returned if token does not exist, is expired, revoked, or used up.
     */
    @Transactional(readOnly = true)
    public InvitePreviewResponse resolvePreview(UUID callerId, String rawToken, String clientIp) {
        rateLimiter.checkResolve(callerId, clientIp);

        String tokenHash = tokenGenerator.hashToken(rawToken);
        GroupInviteLink link = linkRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive"));

        if (!tokenGenerator.constantTimeEquals(link.getTokenHash(), tokenHash) || !link.isValid(Instant.now())) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive");
        }

        Conversation conversation = link.getConversation();
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw ApiException.of(ApiErrorCode.NOT_A_GROUP, "Not a group conversation");
        }

        int memberCount = (int) memberRepository.countByConversation_Id(conversation.getId());
        boolean isMember = callerId != null && memberRepository.existsByConversation_IdAndUser_Id(conversation.getId(), callerId);

        return new InvitePreviewResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getAvatarUrl(),
                memberCount,
                link.isRequiresApproval(),
                isMember);
    }

    /**
     * Attempts to join a group via an invite link.
     * Idempotent: already a member returns ALREADY_MEMBER.
     * If approval required, creates or returns PENDING join request.
     * If approval not required, directly joins the group.
     * Serialized under a row lock to enforce member cap and max uses.
     */
    @Transactional
    public JoinResponse join(UUID callerId, String rawToken, String clientIp) {
        rateLimiter.checkJoin(callerId, clientIp);

        String tokenHash = tokenGenerator.hashToken(rawToken);
        GroupInviteLink link = linkRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive"));

        if (!tokenGenerator.constantTimeEquals(link.getTokenHash(), tokenHash) || !link.isValid(Instant.now())) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive");
        }

        UUID linkId = link.getId();
        UUID conversationId = link.getConversation().getId();
        if (link.getConversation().getType() != Conversation.Type.GROUP) {
            throw ApiException.of(ApiErrorCode.NOT_A_GROUP, "Not a group conversation");
        }
        entityManager.detach(link);

        // Idempotent: already a member
        if (memberRepository.existsByConversation_IdAndUser_Id(conversationId, callerId)) {
            return new JoinResponse("ALREADY_MEMBER", conversationId, null);
        }

        // Banned / removed-by-admin check
        if (banRepository.isBannedFromLink(conversationId, callerId, linkId)) {
            throw ApiException.of(ApiErrorCode.FORBIDDEN,
                    "User was removed from this group by admin and cannot rejoin via this link");
        }

        // Lock conversation row to serialize member cap check across concurrent joiners
        conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));

        long memberCount = memberRepository.countByConversation_Id(conversationId);
        if (memberCount >= maxMembers) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Group has reached maximum member capacity",
                    Map.of("limit", maxMembers));
        }

        // Lock link row to serialize max_uses check
        GroupInviteLink lockedLink = linkRepository.findByIdForUpdate(linkId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive"));

        if (!lockedLink.isValid(Instant.now())) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive");
        }

        if (!lockedLink.isRequiresApproval()) {
            if (linkRepository.tryConsumeUse(linkId) == 0) {
                throw ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive");
            }

            groupService.addMemberFromInvite(callerId, conversationId, null);
            log.info("User {} joined group {} directly via invite link", callerId, conversationId);
            return new JoinResponse("JOINED", conversationId, null);
        } else {
            // Approval required: check existing pending request
            Optional<GroupJoinRequest> pending = joinRequestRepository
                    .findByConversation_IdAndUser_IdAndStatus(conversationId, callerId, GroupJoinRequest.Status.PENDING);
            if (pending.isPresent()) {
                return new JoinResponse("PENDING", conversationId, pending.get().getId());
            }

            // Check if latest request was rejected
            Optional<GroupJoinRequest> latest = joinRequestRepository
                    .findFirstByConversation_IdAndUser_IdOrderByCreatedAtDesc(conversationId, callerId);
            if (latest.isPresent() && latest.get().getStatus() == GroupJoinRequest.Status.REJECTED) {
                throw ApiException.of(ApiErrorCode.FORBIDDEN, "Your request to join this group was rejected");
            }

            User user = userRepository.findById(callerId)
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));

            GroupJoinRequest joinRequest = new GroupJoinRequest(lockedLink.getConversation(), lockedLink, user);
            joinRequest = joinRequestRepository.save(joinRequest);

            log.info("Join request created for user {} in group {}", callerId, conversationId);
            return new JoinResponse("PENDING", conversationId, joinRequest.getId(), true);
        }
    }

    /**
     * Lists join requests for a group (ADMIN only).
     */
    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listJoinRequests(
            UUID callerId, UUID conversationId, GroupJoinRequest.Status status, Integer limit) {
        requireGroup(conversationId);
        ConversationMember callerMember = requireMember(conversationId, callerId);
        requireAdmin(callerMember);

        int pageSize = resolveJoinRequestLimit(limit);
        PageRequest page = PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));

        List<GroupJoinRequest> requests;
        if (status != null) {
            requests = joinRequestRepository.findByConversation_IdAndStatusOrderByCreatedAtDescIdDesc(
                    conversationId, status, page);
        } else {
            requests = joinRequestRepository.findByConversation_IdOrderByCreatedAtDescIdDesc(conversationId, page);
        }
        return requests.stream().map(this::toJoinRequestResponse).toList();
    }

    /**
     * Returns the caller's own latest join request status for a group.
     */
    @Transactional(readOnly = true)
    public JoinRequestResponse getMyJoinRequest(UUID callerId, UUID conversationId) {
        requireGroup(conversationId);
        GroupJoinRequest request = joinRequestRepository
                .findFirstByConversation_IdAndUser_IdOrderByCreatedAtDesc(conversationId, callerId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "No join request found"));

        return toJoinRequestResponse(request);
    }

    /**
     * Approves a pending join request (ADMIN only).
     */
    @Transactional
    public JoinRequestResponse approveJoinRequest(UUID callerId, UUID conversationId, UUID requestId) {
        requireGroup(conversationId);
        ConversationMember callerMember = requireMember(conversationId, callerId);
        requireAdmin(callerMember);

        GroupJoinRequest request = joinRequestRepository.findById(requestId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Join request not found"));

        if (!request.getConversation().getId().equals(conversationId)) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "Join request not found for this group");
        }

        if (request.getStatus() == GroupJoinRequest.Status.APPROVED) {
            return toJoinRequestResponse(request); // Idempotent
        }
        if (request.getStatus() == GroupJoinRequest.Status.REJECTED) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Cannot approve a rejected request");
        }

        // Lock conversation row for member cap
        conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));

        long memberCount = memberRepository.countByConversation_Id(conversationId);
        if (memberCount >= maxMembers) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Group has reached maximum member capacity",
                    Map.of("limit", maxMembers));
        }

        UUID inviteLinkId = request.getInviteLink() != null ? request.getInviteLink().getId() : null;
        if (inviteLinkId != null) {
            linkRepository.findByIdForUpdate(inviteLinkId)
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive"));
            if (linkRepository.tryConsumeUse(inviteLinkId) == 0) {
                throw ApiException.of(ApiErrorCode.NOT_FOUND, "Invite link not found or inactive");
            }
        }

        // Add member to group
        groupService.addMemberFromInvite(request.getUser().getId(), conversationId, callerId);

        request.approve(callerMember.getUser(), Instant.now());
        request = joinRequestRepository.save(request);

        log.info("Join request {} approved by admin {} for user {}", requestId, callerId, request.getUser().getId());
        return toJoinRequestResponse(request);
    }

    /**
     * Rejects a pending join request (ADMIN only).
     */
    @Transactional
    public JoinRequestResponse rejectJoinRequest(UUID callerId, UUID conversationId, UUID requestId) {
        requireGroup(conversationId);
        ConversationMember callerMember = requireMember(conversationId, callerId);
        requireAdmin(callerMember);

        GroupJoinRequest request = joinRequestRepository.findById(requestId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Join request not found"));

        if (!request.getConversation().getId().equals(conversationId)) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "Join request not found for this group");
        }

        if (request.getStatus() == GroupJoinRequest.Status.REJECTED) {
            return toJoinRequestResponse(request); // Idempotent
        }
        if (request.getStatus() == GroupJoinRequest.Status.APPROVED) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Cannot reject an already approved request");
        }

        request.reject(callerMember.getUser(), Instant.now());
        request = joinRequestRepository.save(request);

        log.info("Join request {} rejected by admin {} for user {}", requestId, callerId, request.getUser().getId());
        return toJoinRequestResponse(request);
    }

    // ------------------------------------------------------------- helpers

    private Conversation requireGroup(UUID conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw ApiException.of(ApiErrorCode.NOT_A_GROUP, "Invite links are only supported for GROUP conversations");
        }
        return conversation;
    }

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
    }

    private static void requireAdmin(ConversationMember member) {
        if (member.getRole() != ConversationMember.Role.ADMIN) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Group admin role required");
        }
    }

    private static int resolveJoinRequestLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_JOIN_REQUEST_LIST_LIMIT;
        }
        if (limit < 1) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "limit must be at least 1");
        }
        return Math.min(limit, MAX_JOIN_REQUEST_LIST_LIMIT);
    }

    private InviteLinkResponse toInviteLinkResponse(GroupInviteLink link, String rawToken) {
        return new InviteLinkResponse(
                link.getId(),
                link.getConversation().getId(),
                rawToken,
                link.isRequiresApproval(),
                link.getExpiresAt(),
                link.getMaxUses(),
                link.getUseCount(),
                link.getCreatedAt());
    }

    private JoinRequestResponse toJoinRequestResponse(GroupJoinRequest req) {
        JoinRequestResponse.UserSummary userSummary = new JoinRequestResponse.UserSummary(
                req.getUser().getId(),
                req.getUser().getDisplayName(),
                req.getUser().getAvatarUrl());
        return new JoinRequestResponse(
                req.getId(),
                req.getConversation().getId(),
                userSummary,
                req.getStatus().name(),
                req.getCreatedAt(),
                req.getReviewedAt(),
                req.getReviewedBy() != null ? req.getReviewedBy().getId() : null);
    }
}

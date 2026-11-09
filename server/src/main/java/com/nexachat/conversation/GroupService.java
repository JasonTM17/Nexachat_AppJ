package com.nexachat.conversation;

import com.nexachat.attachment.AvatarController.AvatarResponse;
import com.nexachat.attachment.StorageService;
import com.nexachat.common.MagicBytes;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.conversation.dto.ConversationMemberView;
import com.nexachat.conversation.dto.ConversationResponse;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.conversation.dto.CreateGroupRequest;
import com.nexachat.conversation.dto.MemberResponse;
import com.nexachat.conversation.dto.PatchGroupRequest;
import com.nexachat.message.MessageService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GROUP use cases (plan §10.2, phase-08): create, member add/remove, self-leave,
 * rename/avatar. The DIRECT path lives in {@link ConversationService} — this
 * service only accepts {@code type='GROUP'} conversations.
 *
 * <p><b>Invariants:</b>
 * <ul>
 *   <li>A GROUP has ≥ 2 people at creation: creator (ADMIN) plus ≥ 1 listed
 *       member (deduped, creator-skipped) — phase-08 "≥2 members".</li>
 *   <li>A group with members must keep ≥ 1 ADMIN: the SOLE admin cannot leave
 *       or be removed while other members remain → {@code VALIDATION_FAILED}
 *       (a domain invariant, not a permission question — no caller may break
 *       it, hence 400 not 403). The client must promote another admin first;
 *       an admin-promotion endpoint is intentionally out of scope.</li>
 *   <li>The LAST member may leave — the conversation row, title and message
 *       history are NOT deleted; the group simply becomes unowned (nobody can
 *       read or act on it: every member op fails {@code NOT_MEMBER}).</li>
 * </ul>
 *
 * <p><b>Side effects:</b> every mutation that changes visible state writes a
 * {@code SYSTEM} message via {@link MessageService#postSystemMessage} (so the
 * same persist-before-broadcast path delivers {@code MESSAGE_CREATED}), then
 * publishes a {@link ConversationUpdatedEvent} that fans out
 * {@code CONVERSATION_UPDATED} to the topic plus every affected
 * {@code /user/queue/inbox} — including users who just stopped being members
 * ({@link ConversationUpdatedEvent#extraAudienceUserIds()}).
 */
@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    static final int MAX_TITLE_LENGTH = 80;
    static final int MAX_AVATAR_URL_LENGTH = 255;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;
    private final StorageService storageService;
    private final SimpUserRegistry simpUserRegistry;
    private final AbstractBrokerMessageHandler simpleBrokerMessageHandler;

    public GroupService(ConversationRepository conversationRepository,
                        ConversationMemberRepository memberRepository,
                        UserRepository userRepository,
                        MessageService messageService,
                        ApplicationEventPublisher eventPublisher,
                        StorageService storageService,
                        SimpUserRegistry simpUserRegistry,
                        AbstractBrokerMessageHandler simpleBrokerMessageHandler) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.eventPublisher = eventPublisher;
        this.storageService = storageService;
        this.simpUserRegistry = simpUserRegistry;
        this.simpleBrokerMessageHandler = simpleBrokerMessageHandler;
    }

    /**
     * Creates a GROUP conversation: creator becomes ADMIN, every listed member
     * becomes MEMBER, all in this one transaction. {@code memberIds} is
     * deduped and the creator is dropped if listed; at least one other member
     * must remain. A {@code SYSTEM} "created the group" line seeds the history
     * (and the denormalized last-message pointer, so the group surfaces in
     * inboxes immediately).
     *
     * @throws ApiException {@code VALIDATION_FAILED} (bad title / empty
     *                      member list), {@code USER_NOT_FOUND} (creator or a
     *                      listed member does not exist)
     */
    @Transactional
    public ConversationResponse createGroup(UUID creatorId, CreateGroupRequest request) {
        String title = normalizeTitle(request == null ? null : request.title());
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));

        List<UUID> memberIds = dedupeExcluding(
                request == null ? null : request.memberIds(), creatorId);
        if (memberIds.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Group needs at least one other member");
        }
        List<User> members = resolveUsers(memberIds);

        Conversation conversation = Conversation.group(creator, title);
        conversationRepository.save(conversation);

        List<ConversationMember> rows = new ArrayList<>(members.size() + 1);
        rows.add(ConversationMember.join(
                conversation, creator, ConversationMember.Role.ADMIN));
        for (User member : members) {
            rows.add(ConversationMember.join(
                    conversation, member, ConversationMember.Role.MEMBER));
        }
        memberRepository.saveAll(rows);

        messageService.postSystemMessage(creatorId, conversation.getId(),
                creator.getDisplayName() + " created the group");
        publishUpdate(conversation, "CREATED", List.of());

        log.info("Group conversation {} created by {} with {} members",
                conversation.getId(), creatorId, rows.size());
        List<ConversationMemberView> memberViews =
                memberRepository.findMemberViews(conversation.getId());
        return new ConversationResponse(
                conversation.getId(), conversation.getType().name(), memberViews,
                conversation.getCreatedAt(), false);
    }

    /**
     * Adds users to a GROUP — ADMIN only, idempotent: ids that already belong
     * to the group are skipped, not errors. One {@code SYSTEM} line is posted
     * per actually-added user ("Alice added Bob"), each broadcast as
     * {@code MESSAGE_CREATED}; one {@code CONVERSATION_UPDATED} snapshot then
     * covers the whole add.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND},
     *                      {@code VALIDATION_FAILED} (not a GROUP / empty
     *                      list), {@code NOT_MEMBER}, {@code NOT_ADMIN},
     *                      {@code USER_NOT_FOUND}
     */
    @Transactional
    public void addMembers(UUID callerId, UUID conversationId, AddMemberRequest request) {
        Conversation conversation = requireGroup(conversationId);
        ConversationMember caller = requireMember(conversationId, callerId);
        requireAdmin(caller);

        List<UUID> userIds = dedupeExcluding(
                request == null ? null : request.userIds(), null);
        if (userIds.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "userIds must not be empty");
        }
        List<User> candidates = resolveUsers(userIds);

        Set<UUID> existing = new HashSet<>(memberRepository.findMemberUserIds(conversationId));
        List<User> toAdd = candidates.stream()
                .filter(user -> !existing.contains(user.getId()))
                .toList();
        if (toAdd.isEmpty()) {
            return; // idempotent no-op: nothing changed → no message, no broadcast
        }

        String actorName = caller.getUser().getDisplayName();
        for (User user : toAdd) {
            memberRepository.save(ConversationMember.join(
                    conversation, user, ConversationMember.Role.MEMBER));
        }
        for (User user : toAdd) {
            messageService.postSystemMessage(callerId, conversationId,
                    actorName + " added " + user.getDisplayName());
        }
        publishUpdate(conversation, "MEMBERS_ADDED", List.of());
        log.info("{} added {} member(s) to group {}", callerId, toAdd.size(), conversationId);
    }

    /**
     * Adds a member to a GROUP when joining via invite link or approval.
     * Checks that the conversation is a GROUP and that the member is not already present.
     * If already present, acts idempotently without re-posting a system message.
     * Posts a SYSTEM message and publishes CONVERSATION_UPDATED event.
     */
    @Transactional
    public void addMemberFromInvite(UUID userId, UUID conversationId, UUID approverId) {
        Conversation conversation = requireGroup(conversationId);
        if (memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            return; // Idempotent no-op
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        memberRepository.saveAndFlush(ConversationMember.join(
                conversation, user, ConversationMember.Role.MEMBER));

        String text;
        if (approverId != null) {
            User approver = userRepository.findById(approverId).orElse(null);
            String approverName = approver != null ? approver.getDisplayName() : "Admin";
            text = approverName + " approved " + user.getDisplayName() + " to join the group";
        } else {
            text = user.getDisplayName() + " joined the group via invite link";
        }
        UUID actorId = approverId != null ? approverId : userId;
        messageService.postSystemMessage(actorId, conversationId, text);
        publishUpdate(conversation, "MEMBER_JOINED", List.of());
        log.info("User {} joined group {} via invite link (approver: {})", userId, conversationId, approverId);
    }

    /**
     * Removes a member — ADMIN removing another user, OR any member removing
     * themselves (self-leave via DELETE, per plan §10.2 "ADMIN hoặc chính
     * userId"). A non-self remove requires {@code NOT_MEMBER}-checked admin.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND},
     *                      {@code VALIDATION_FAILED} (not a GROUP / removing
     *                      the sole ADMIN while members remain),
     *                      {@code NOT_MEMBER} (caller or target),
     *                      {@code NOT_ADMIN}
     */
    @Transactional
    public void removeMember(UUID callerId, UUID conversationId, UUID targetUserId) {
        Conversation conversation = requireGroup(conversationId);
        removeMemberInternal(conversation, callerId, targetUserId);
    }

    /**
     * Self-leave (POST /{id}/leave) — any member. Sole ADMIN with other
     * members remaining is rejected; the last member may leave (the empty
     * conversation persists — see class doc).
     */
    @Transactional
    public void leave(UUID callerId, UUID conversationId) {
        Conversation conversation = requireGroup(conversationId);
        removeMemberInternal(conversation, callerId, callerId);
    }

    private void removeMemberInternal(Conversation conversation, UUID callerId, UUID targetUserId) {
        UUID conversationId = conversation.getId();
        ConversationMember caller = requireMember(conversationId, callerId);
        boolean selfRemoval = targetUserId.equals(callerId);
        if (!selfRemoval) {
            // AuthZ before target resolution: a non-admin learns nothing about
            // other users' membership state.
            requireAdmin(caller);
        }
        ConversationMember target = memberRepository
                .findByConversation_IdAndUser_Id(conversationId, targetUserId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "User is not a member of this group"));
        if (target.getRole() == ConversationMember.Role.ADMIN
                && memberRepository.countByConversation_IdAndRole(
                        conversationId, ConversationMember.Role.ADMIN) <= 1
                && memberRepository.countByConversation_Id(conversationId) > 1) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "The last admin cannot leave or be removed — assign another admin first");
        }

        String actorName = caller.getUser().getDisplayName();
        // The system line is posted BEFORE the member row is deleted so the
        // acting user is still a member when postSystemMessage checks.
        messageService.postSystemMessage(callerId, conversationId, selfRemoval
                ? actorName + " left the group"
                : actorName + " removed " + target.getUser().getDisplayName());
        memberRepository.delete(target);
        revokeTopicSubscriptionsAfterCommit(targetUserId, conversationId);
        publishUpdate(conversation,
                selfRemoval ? "MEMBER_LEFT" : "MEMBER_REMOVED", List.of(targetUserId));
        log.info("{} {} {} in group {}",
                callerId, selfRemoval ? "left" : "removed", targetUserId, conversationId);
    }

    private void revokeTopicSubscriptionsAfterCommit(UUID userId, UUID conversationId) {
        Runnable revoke = () -> revokeTopicSubscriptions(userId, conversationId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            revoke.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                revoke.run();
            }
        });
    }

    private void revokeTopicSubscriptions(UUID userId, UUID conversationId) {
        SimpUser user = simpUserRegistry.getUser(userId.toString());
        if (user == null) {
            return;
        }
        String destination = "/topic/conversations." + conversationId;
        for (SimpSession session : List.copyOf(user.getSessions())) {
            List<SimpSubscription> subscriptions = session.getSubscriptions().stream()
                    .filter(subscription -> destination.equals(subscription.getDestination()))
                    .toList();
            for (SimpSubscription subscription : subscriptions) {
                StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
                headers.setSessionId(session.getId());
                headers.setSubscriptionId(subscription.getId());
                headers.setUser(user.getPrincipal());
                Message<byte[]> unsubscribe = MessageBuilder.createMessage(
                        new byte[0], headers.getMessageHeaders());
                if (simpleBrokerMessageHandler instanceof SimpleBrokerMessageHandler broker) {
                    broker.getSubscriptionRegistry().unregisterSubscription(unsubscribe);
                }
                eventPublisher.publishEvent(new SessionUnsubscribeEvent(
                        this, unsubscribe, user.getPrincipal()));
            }
        }
    }

    /**
     * Renames / re-avatars a GROUP — ADMIN only. At least one field must be
     * present. A {@code SYSTEM} "renamed" line is posted ONLY when the title
     * actually changed; {@code CONVERSATION_UPDATED} is broadcast regardless
     * (avatar-only changes still need client resync).
     */
    @Transactional
    public ConversationUpdatePayload updateGroup(
            UUID callerId, UUID conversationId, PatchGroupRequest request) {
        Conversation conversation = requireGroup(conversationId);
        ConversationMember caller = requireMember(conversationId, callerId);
        requireAdmin(caller);
        if (request == null || (request.title() == null && request.avatarUrl() == null)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Provide title and/or avatarUrl");
        }

        boolean renamed = false;
        if (request.title() != null) {
            String newTitle = normalizeTitle(request.title());
            renamed = !newTitle.equals(conversation.getTitle());
            if (renamed) {
                conversation.changeTitle(newTitle);
            }
        }
        if (request.avatarUrl() != null) {
            String avatarUrl = request.avatarUrl().trim();
            if (avatarUrl.length() > MAX_AVATAR_URL_LENGTH) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "avatarUrl must be at most " + MAX_AVATAR_URL_LENGTH + " characters");
            }
            conversation.changeAvatarUrl(avatarUrl.isEmpty() ? null : avatarUrl);
        }

        if (renamed) {
            messageService.postSystemMessage(callerId, conversationId,
                    caller.getUser().getDisplayName() + " renamed the group to \""
                            + conversation.getTitle() + "\"");
        }
        return publishUpdate(conversation, "UPDATED", List.of());
    }

    /**
     * Group avatar upload (FR-D4): ADMIN only. Same sniff+store contract as
     * {@code POST /users/me/avatar} — only image content passes, the file is
     * kept under {@code avatars/} and served from the authed files endpoint.
     * {@code CONVERSATION_UPDATED} carries the new {@code avatarUrl} so every
     * member resyncs without a pull.
     */
    @Transactional
    public AvatarResponse setGroupAvatar(
            UUID callerId, UUID conversationId, MultipartFile file) throws IOException {
        Conversation conversation = requireGroup(conversationId);
        requireAdmin(requireMember(conversationId, callerId));
        if (file == null || file.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Avatar file is required");
        }
        if (file.getSize() > StorageService.MAX_FILE_SIZE_BYTES) {
            throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
        }
        byte[] bytes = file.getBytes();
        String sniffed = MagicBytes.sniff(bytes);
        if (sniffed == null || !sniffed.startsWith("image/")) {
            throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
        }
        StorageService.StoredFile stored = storageService.store(
                "avatars", file.getOriginalFilename(), file.getContentType(), bytes);
        conversation.changeAvatarUrl(storageService.resolveUrl(stored.storageKey()));
        publishUpdate(conversation, "UPDATED", List.of());
        log.info("Group {} avatar set by {}", conversationId, callerId);
        return new AvatarResponse(conversation.getAvatarUrl());
    }

    // ------------------------------------------------------------- internals

    private Conversation requireGroup(UUID conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Group operations only apply to GROUP conversations");
        }
        return conversation;
    }

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
    }

    private static void requireAdmin(ConversationMember member) {
        if (member.getRole() != ConversationMember.Role.ADMIN) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Group admin role required");
        }
    }

    private static String normalizeTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "title is required");
        }
        String title = rawTitle.trim();
        if (title.length() > MAX_TITLE_LENGTH) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        return title;
    }

    /** Deduped, null-free id list; {@code excludeId} (the creator/self) is dropped. */
    private static List<UUID> dedupeExcluding(List<UUID> ids, UUID excludeId) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(excludeId))
                .distinct()
                .toList();
    }

    /**
     * Resolves ids to users in REQUEST order; any id that does not exist aborts
     * the whole call with {@code USER_NOT_FOUND} (no partial adds).
     */
    private List<User> resolveUsers(List<UUID> userIds) {
        Map<UUID, User> byId = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<User> resolved = new ArrayList<>(userIds.size());
        List<UUID> missing = new ArrayList<>();
        for (UUID id : userIds) {
            User user = byId.get(id);
            if (user == null) {
                missing.add(id);
            } else {
                resolved.add(user);
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found",
                    Map.of("userIds", missing));
        }
        return resolved;
    }

    /**
     * Emits the {@code CONVERSATION_UPDATED} signal with a post-mutation member
     * snapshot (the JPQL read auto-flushes pending member writes first).
     *
     * @return the payload carried by the event — reused as the PATCH body
     */
    private ConversationUpdatePayload publishUpdate(
            Conversation conversation, String change, List<UUID> extraAudienceUserIds) {
        List<MemberResponse> members = memberRepository.findMembers(conversation.getId());
        ConversationUpdatePayload payload = new ConversationUpdatePayload(
                conversation.getId(), conversation.getTitle(), conversation.getAvatarUrl(),
                change, members);
        eventPublisher.publishEvent(new ConversationUpdatedEvent(
                conversation.getId(), payload, extraAudienceUserIds));
        return payload;
    }
}

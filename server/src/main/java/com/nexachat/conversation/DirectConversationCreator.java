package com.nexachat.conversation;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Writes one DIRECT conversation + its two member rows atomically.
 *
 * <p>This lives on its own bean so the insert runs inside a dedicated
 * transaction ({@code REQUIRES_NEW}) — that is what lets
 * {@link ConversationService#openDirect} catch a
 * {@code DataIntegrityViolationException} on the {@code direct_key} UNIQUE and
 * then re-read: PostgreSQL aborts the whole transaction on the first failed
 * statement, so a SELECT issued after the failed flush inside the SAME
 * transaction would itself error out (25P02). A separate, rollback-only
 * insert transaction keeps the loser's re-read path clean while the winner's
 * row commits normally.
 */
@Component
public class DirectConversationCreator {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DirectConversationCreator(ConversationRepository conversationRepository,
                                     ConversationMemberRepository memberRepository,
                                     UserRepository userRepository,
                                     ApplicationEventPublisher eventPublisher) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Conversation createDirect(UUID callerId, UUID otherId, String directKey) {
        List<UUID> userIds = List.of(callerId, otherId).stream().distinct().sorted().toList();
        Map<UUID, User> users = userRepository.findActiveUsersByIdsForUpdate(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        User caller = requireActiveUser(users.get(callerId));
        User other = requireActiveUser(users.get(otherId));
        Conversation conversation = Conversation.direct(caller, directKey);
        // saveAndFlush, not save: the INSERT must hit the direct_key UNIQUE
        // NOW — save() alone defers the flush to commit, after the caller has
        // already exited the catch scope that maps the race.
        conversation = conversationRepository.saveAndFlush(conversation);
        memberRepository.saveAllAndFlush(List.of(
                ConversationMember.join(conversation, caller, ConversationMember.Role.MEMBER),
                ConversationMember.join(conversation, other, ConversationMember.Role.MEMBER)));
        eventPublisher.publishEvent(new ConversationMembershipChangedEvent(
                conversation.getId(), List.of()));
        return conversation;
    }

    private static User requireActiveUser(User user) {
        if (user == null || user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        return user;
    }
}

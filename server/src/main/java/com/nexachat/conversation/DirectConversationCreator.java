package com.nexachat.conversation;

import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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

    public DirectConversationCreator(ConversationRepository conversationRepository,
                                     ConversationMemberRepository memberRepository,
                                     UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Conversation createDirect(UUID callerId, UUID otherId, String directKey) {
        User caller = userRepository.getReferenceById(callerId);
        User other = userRepository.getReferenceById(otherId);
        Conversation conversation = Conversation.direct(caller, directKey);
        // saveAndFlush, not save: the INSERT must hit the direct_key UNIQUE
        // NOW — save() alone defers the flush to commit, after the caller has
        // already exited the catch scope that maps the race.
        conversation = conversationRepository.saveAndFlush(conversation);
        memberRepository.saveAllAndFlush(List.of(
                ConversationMember.join(conversation, caller, ConversationMember.Role.MEMBER),
                ConversationMember.join(conversation, other, ConversationMember.Role.MEMBER)));
        return conversation;
    }
}

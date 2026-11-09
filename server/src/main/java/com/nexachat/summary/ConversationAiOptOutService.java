package com.nexachat.summary;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Member-scoped, idempotent AI opt-out use cases. The state applies only to
 * the caller's own messages in the conversation.
 */
@Service
@Profile("!test")
public class ConversationAiOptOutService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final JdbcConversationAiOptOutRepository repository;

    public ConversationAiOptOutService(
            ConversationRepository conversationRepository,
            ConversationMemberRepository memberRepository,
            JdbcConversationAiOptOutRepository repository) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ConversationAiOptOutResponse read(UUID conversationId, UUID userId) {
        requireMember(conversationId, userId);
        return new ConversationAiOptOutResponse(
                conversationId, repository.isOptedOut(conversationId, userId));
    }

    @Transactional
    public ConversationAiOptOutResponse optOut(UUID conversationId, UUID userId) {
        requireMember(conversationId, userId);
        repository.optOut(conversationId, userId);
        return new ConversationAiOptOutResponse(conversationId, true);
    }

    @Transactional
    public ConversationAiOptOutResponse clear(UUID conversationId, UUID userId) {
        requireMember(conversationId, userId);
        repository.clearOptOut(conversationId, userId);
        return new ConversationAiOptOutResponse(conversationId, false);
    }

    private void requireMember(UUID conversationId, UUID userId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Conversation membership required");
        }
    }
}

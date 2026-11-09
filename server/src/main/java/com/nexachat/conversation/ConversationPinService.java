package com.nexachat.conversation;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-member conversation pin: {@code PUT/DELETE /conversations/{id}/pin}.
 *
 * <p>Every operation loads and mutates only the caller's own
 * {@code conversation_members} row, so a pin is never visible to, or changes
 * the order for, another member. Both verbs are idempotent: pinning an
 * already pinned conversation (even at the ceiling) and unpinning a never
 * pinned conversation both succeed without changing stored state.
 *
 * <p><b>Ceiling race safety:</b> a NEW pin takes a {@code SELECT ... FOR
 * UPDATE} on the caller's {@code users} row before counting. Concurrent pins
 * by the same user therefore serialize, and the fifth slot cannot be handed
 * out twice. The remaining slot check runs after the lock, on a fresh count.
 *
 * <p>AuthZ follows the codebase order: conversation existence ({@code 404})
 * before membership ({@code 403}) before the pin ceiling ({@code 409}).
 */
@Service
public class ConversationPinService {

    /** Zalo-style per-user ceiling on pinned conversations. */
    static final int MAX_PINNED_CONVERSATIONS = 5;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public ConversationPinService(ConversationRepository conversationRepository,
                                  ConversationMemberRepository memberRepository,
                                  UserRepository userRepository) {
        this(conversationRepository, memberRepository, userRepository, Clock.systemUTC());
    }

    ConversationPinService(ConversationRepository conversationRepository,
                           ConversationMemberRepository memberRepository,
                           UserRepository userRepository,
                           Clock clock) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * Pins the conversation for the caller.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code USER_NOT_FOUND},
     *                      {@code NOT_MEMBER}, {@code CONVERSATION_PIN_LIMIT}
     */
    @Transactional
    public void pin(UUID userId, UUID conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        // Serializes every NEW pin by this user: the count below runs after
        // any racing pin transaction committed, so two calls cannot both
        // consume the same remaining slot.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        ConversationMember member = requireMember(conversationId, userId);
        if (member.isPinned()) {
            return;
        }
        if (memberRepository.countPinnedByUserId(userId) >= MAX_PINNED_CONVERSATIONS) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_PIN_LIMIT,
                    "At most " + MAX_PINNED_CONVERSATIONS + " pinned conversations per user");
        }
        member.pin(clock.instant());
        memberRepository.save(member);
    }

    /**
     * Unpins the conversation for the caller. Idempotent.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER}
     */
    @Transactional
    public void unpin(UUID userId, UUID conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        ConversationMember member = requireMember(conversationId, userId);
        member.unpin();
        memberRepository.save(member);
    }

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
    }
}

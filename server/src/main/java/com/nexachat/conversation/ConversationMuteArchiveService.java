package com.nexachat.conversation;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.MuteRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-member conversation mute and archive preferences.
 *
 * <p>Every operation loads the caller's own {@code conversation_members} row and
 * mutates only that row, so one member's preference can never be observed or
 * changed by another member. AuthZ follows the codebase order: conversation
 * existence ({@code 404}) before membership ({@code 403}) before body validation
 * ({@code 400}), so a non-member cannot probe which bodies would be valid.
 *
 * <p>All four operations are idempotent: repeating an identical call converges
 * on the same stored state and returns success.
 */
@Service
public class ConversationMuteArchiveService {

    /** Upper bound for a timed mute; {@code forever} is the explicit escape hatch. */
    static final Duration MAX_MUTE_DURATION = Duration.ofDays(365);

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final Clock clock;

    @Autowired
    public ConversationMuteArchiveService(ConversationRepository conversationRepository,
                                          ConversationMemberRepository memberRepository) {
        this(conversationRepository, memberRepository, Clock.systemUTC());
    }

    ConversationMuteArchiveService(ConversationRepository conversationRepository,
                                   ConversationMemberRepository memberRepository,
                                   Clock clock) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.clock = clock;
    }

    /**
     * Mutes the caller's view of the conversation forever or until
     * {@code request.mutedUntil}.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER},
     *                      {@code VALIDATION_FAILED}
     */
    @Transactional
    public void mute(UUID userId, UUID conversationId, MuteRequest request) {
        ConversationMember member = requireMember(conversationId, userId);
        Instant now = clock.instant();
        boolean forever = request != null && Boolean.TRUE.equals(request.forever());
        Instant until = request == null ? null : request.mutedUntil();

        if (forever) {
            if (until != null) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Specify only one of mutedUntil or forever");
            }
            member.mute(null);
        } else {
            if (until == null) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Specify mutedUntil or forever");
            }
            if (!until.isAfter(now)) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "mutedUntil must be in the future");
            }
            if (until.isAfter(now.plus(MAX_MUTE_DURATION))) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "mutedUntil must be at most 365 days from now");
            }
            member.mute(until);
        }
        memberRepository.save(member);
    }

    /**
     * Clears the caller's mute. Idempotent: clearing an unmuted row is a no-op.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER}
     */
    @Transactional
    public void unmute(UUID userId, UUID conversationId) {
        ConversationMember member = requireMember(conversationId, userId);
        member.unmute();
        memberRepository.save(member);
    }

    /**
     * Archives the caller's view of the conversation (hidden from the default
     * inbox). Idempotent.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER}
     */
    @Transactional
    public void archive(UUID userId, UUID conversationId) {
        ConversationMember member = requireMember(conversationId, userId);
        member.archive();
        memberRepository.save(member);
    }

    /**
     * Restores an archived conversation to the caller's default inbox. Idempotent.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER}
     */
    @Transactional
    public void unarchive(UUID userId, UUID conversationId) {
        ConversationMember member = requireMember(conversationId, userId);
        member.unarchive();
        memberRepository.save(member);
    }

    /**
     * Loads the caller's membership row, enforcing the 404-before-403 order.
     */
    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        return memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
    }
}

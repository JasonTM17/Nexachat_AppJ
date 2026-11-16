package com.nexachat.disappearing;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.ConversationUpdatedEvent;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.disappearing.dto.DisappearingRequest;
import com.nexachat.message.MessageService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The per-conversation disappearing-message timer. Setting it affects only
 * messages sent AFTER the change — {@code messages.expires_at} is stamped at
 * insert time, so existing history is never retroactively expired.
 *
 * <p>Semantics: {@code 0} clears the timer (stored as NULL); the allow-listed
 * durations are the only accepted values. In a GROUP only admins may change
 * it; in a DIRECT conversation any member may. Every effective change writes
 * a {@code SYSTEM} line and publishes {@code CONVERSATION_UPDATED} with the
 * new state so online members and offline sync converge on the same value.
 */
@Service
public class DisappearingMessagesService {

    /** Allowed timer values in seconds; {@code 0} is the explicit "off" value. */
    public static final Set<Integer> ALLOWED_SECONDS =
            Set.of(0, 3600, 86400, 604800, 2592000, 7776000);

    /** English duration labels embedded in the SYSTEM line (client localizes them). */
    private static final Map<Integer, String> DURATION_LABELS = Map.of(
            3600, "1 hour",
            86400, "1 day",
            604800, "7 days",
            2592000, "30 days",
            7776000, "90 days");

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;

    public DisappearingMessagesService(ConversationRepository conversationRepository,
                                       ConversationMemberRepository memberRepository,
                                       UserRepository userRepository,
                                       MessageService messageService,
                                       ApplicationEventPublisher eventPublisher) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Sets or clears the conversation timer under the conversation row lock —
     * the same lock {@code MessageService.send} holds, so a timer change and a
     * send can never interleave and leave a message stamped with the wrong
     * expiry. The SYSTEM line is posted before the {@code CONVERSATION_UPDATED}
     * frame so ordering clients see the explanation before the state change.
     *
     * @return the update payload (also broadcast); unchanged state is an
     *         idempotent no-op — no duplicate SYSTEM line, no frame
     */
    @Transactional
    public ConversationUpdatePayload setTimer(UUID userId, UUID conversationId,
                                              DisappearingRequest request) {
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        ConversationMember member = memberRepository
                .findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
        if (conversation.getType() == Conversation.Type.GROUP
                && member.getRole() != ConversationMember.Role.ADMIN) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN,
                    "Only a group admin can change disappearing messages");
        }
        int seconds = normalize(request == null ? null : request.seconds());
        int current = conversation.getDisappearingSeconds() == null
                ? 0 : conversation.getDisappearingSeconds();
        if (seconds == current) {
            return payload(conversation, "UPDATED");
        }

        conversation.setDisappearingSeconds(seconds == 0 ? null : seconds);
        conversationRepository.save(conversation);

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        String text = seconds == 0
                ? actor.getDisplayName() + " turned off disappearing messages"
                : actor.getDisplayName() + " set messages to disappear after "
                + DURATION_LABELS.get(seconds);
        messageService.postSystemMessage(userId, conversationId, text);
        return publishUpdate(conversation);
    }

    private static int normalize(Integer seconds) {
        if (seconds == null || !ALLOWED_SECONDS.contains(seconds)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "seconds must be one of " + ALLOWED_SECONDS.stream().sorted().toList());
        }
        return seconds;
    }

    private ConversationUpdatePayload publishUpdate(Conversation conversation) {
        ConversationUpdatePayload payload = payload(conversation, "UPDATED");
        eventPublisher.publishEvent(new ConversationUpdatedEvent(
                conversation.getId(), payload, List.of()));
        return payload;
    }

    private ConversationUpdatePayload payload(Conversation conversation, String change) {
        return new ConversationUpdatePayload(
                conversation.getId(), conversation.getTitle(), conversation.getAvatarUrl(),
                change, memberRepository.findMembers(conversation.getId()),
                conversation.isE2eeEnabled(),
                conversation.getDisappearingSeconds() == null
                        ? 0 : conversation.getDisappearingSeconds());
    }
}

package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.dto.ReactRequest;
import com.nexachat.message.dto.ReactionRow;
import com.nexachat.message.dto.ReactionSlice;
import com.nexachat.message.dto.ReactionUpdatedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Emoji-reaction use case (phase-10 creative feature, plan §10.2):
 * {@code POST /messages/{id}/reactions} with TOGGLE semantics — posting an
 * emoji the caller already placed removes it, otherwise it is added.
 *
 * <p><b>Race safety:</b> no check-then-act window exists in service code.
 * The toggle is a plain {@code DELETE} followed (when nothing was removed)
 * by {@code INSERT ... ON CONFLICT DO NOTHING} — the composite PK
 * {@code (message_id, user_id, emoji)} resolves any interleaving, so two
 * concurrent toggles can never duplicate or corrupt a reaction.
 *
 * <p><b>Broadcast:</b> persist-before-broadcast like {@link MessageService}
 * — the aggregate is computed in-transaction and a {@link ReactionUpdatedEvent}
 * fans out AFTER COMMIT, so the frame only ever describes durable state.
 */
@Service
public class ReactionService {

    private static final Logger log = LoggerFactory.getLogger(ReactionService.class);

    /** {@code message_reactions.emoji} is {@code VARCHAR(16)}. */
    static final int MAX_EMOJI_LENGTH = 16;

    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final ConversationMemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReactionService(MessageRepository messageRepository,
                           MessageReactionRepository reactionRepository,
                           ConversationMemberRepository memberRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.reactionRepository = reactionRepository;
        this.memberRepository = memberRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Toggles {@code emoji} on {@code messageId} for {@code userId}: removes
     * it when present, adds it when absent. Member-only on the message's
     * conversation. Always broadcasts the post-toggle aggregate — a replayed
     * frame is truthful and idempotent for receivers (the payload replaces,
     * never merges, client state).
     *
     * @throws ApiException {@code VALIDATION_FAILED} (blank / &gt;16-char
     *                      emoji), {@code MESSAGE_NOT_FOUND},
     *                      {@code NOT_MEMBER}
     */
    @Transactional
    public void toggle(UUID userId, UUID messageId, ReactRequest request) {
        String emoji = normalizeEmoji(request == null ? null : request.emoji());
        Message message = messageRepository.findByIdForUpdate(messageId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        UUID conversationId = message.getConversation().getId();
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
        if (message.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.MESSAGE_DELETED, "Message was deleted");
        }

        int removed = reactionRepository.deleteOne(messageId, userId, emoji);
        boolean added = removed == 0;
        if (added) {
            reactionRepository.insertIgnoringConflict(messageId, userId, emoji);
        }

        eventPublisher.publishEvent(new ReactionUpdatedEvent(
                conversationId, new ReactionUpdatedPayload(messageId, aggregate(messageId))));
        log.info("Reaction {} {} on message {} by {}", emoji,
                added ? "added" : "removed", messageId, userId);
    }

    /**
     * Post-toggle aggregate for the broadcast: every emoji on the message
     * with its reactor id list, ordered by emoji for a stable wire shape.
     */
    private List<ReactionSlice> aggregate(UUID messageId) {
        Map<String, List<UUID>> byEmoji = new TreeMap<>();
        for (ReactionRow row : reactionRepository.findRowsByMessageIdIn(List.of(messageId))) {
            byEmoji.computeIfAbsent(row.emoji(), k -> new ArrayList<>()).add(row.userId());
        }
        return byEmoji.entrySet().stream()
                .map(e -> new ReactionSlice(e.getKey(), e.getValue().size(), List.copyOf(e.getValue())))
                .toList();
    }

    /**
     * Trims and bounds the emoji to the column width (16 chars, counted as
     * code points so multi-code-unit emoji like flags and ZWJ sequences are
     * measured the way PostgreSQL measures {@code VARCHAR(16)}).
     */
    private static String normalizeEmoji(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "emoji is required");
        }
        String emoji = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (emoji.isEmpty() || emoji.codePointCount(0, emoji.length()) > MAX_EMOJI_LENGTH) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "emoji must be 1-" + MAX_EMOJI_LENGTH + " characters");
        }
        return emoji;
    }
}

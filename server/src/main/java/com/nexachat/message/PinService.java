package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.PinRequest;
import com.nexachat.message.dto.PinUpdatedPayload;
import com.nexachat.message.dto.PinnedMessageView;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pinned-message use cases (phase-10 creative feature, plan §10.2):
 * {@code GET/POST/DELETE /conversations/{id}/pins}.
 *
 * <p><b>Policy:</b> any member pins in a DIRECT conversation; GROUP pinning
 * is ADMIN-only ({@code NOT_ADMIN} otherwise). A conversation holds at most
 * {@value #MAX_PINS_PER_CONVERSATION} pins — a 4th pin fails
 * {@code PIN_LIMIT} (409).
 *
 * <p><b>Race safety:</b> pin and unpin both take a
 * {@code SELECT ... FOR UPDATE} on the conversation row, so the
 * exists/count/insert sequence is serialized per conversation — two racing
 * pins cannot both slip under the ceiling. The composite PK
 * {@code (conversation_id, message_id)} additionally makes a repeat pin a
 * no-op at the DB level.
 *
 * <p><b>Broadcast:</b> persist-before-broadcast — a {@link PinUpdatedEvent}
 * is published inside the transaction and fans out AFTER COMMIT as
 * {@code PIN_UPDATED {messageId, pinned}}. Like the read-watermark path, the
 * event is published even on a no-op: a replayed frame states the truth and
 * is idempotent for receivers.
 */
@Service
public class PinService {

    private static final Logger log = LoggerFactory.getLogger(PinService.class);

    /** Plan §10.2: "tối đa 3" pins per conversation. */
    static final int MAX_PINS_PER_CONVERSATION = 3;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final PinnedMessageRepository pinRepository;
    private final MessageService messageService;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PinService(ConversationRepository conversationRepository,
                      ConversationMemberRepository memberRepository,
                      MessageRepository messageRepository,
                      PinnedMessageRepository pinRepository,
                      MessageService messageService,
                      UserRepository userRepository,
                      ApplicationEventPublisher eventPublisher) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.pinRepository = pinRepository;
        this.messageService = messageService;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The conversation's pinned list, newest pin first (PinnedBanner order).
     * Each entry carries the full {@code MessageResponse}; attachments,
     * reactions and reply previews are batch-hydrated for the bounded list.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER}
     */
    @Transactional(readOnly = true)
    public List<PinnedMessageView> listPins(UUID userId, UUID conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        requireMember(conversationId, userId);

        List<PinnedMessage> pins =
                pinRepository.findByConversation_IdOrderByPinnedAtDesc(conversationId);
        if (pins.isEmpty()) {
            return List.of();
        }
        Map<UUID, MessageRow> rowsById = messageRepository.findAdmittedRowsByIdIn(userId,
                        pins.stream().map(p -> p.getMessage().getId()).toList())
                .stream()
                .collect(Collectors.toMap(MessageRow::id, Function.identity()));

        List<MessageRow> rows = pins.stream()
                .map(pin -> rowsById.get(pin.getMessage().getId()))
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, MessageResponse> responsesById = messageService.toResponses(rows, userId).stream()
                .collect(Collectors.toMap(MessageResponse::id, Function.identity()));

        return pins.stream()
                .map(pin -> {
                    UUID messageId = pin.getMessage().getId();
                    MessageResponse response = responsesById.get(messageId);
                    return response == null ? null : new PinnedMessageView(
                            messageId, pin.getPinnedBy().getId(), pin.getPinnedAt(), response);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Pins {@code messageId} in {@code conversationId}. Idempotent: an
     * already-pinned message is a no-op (not a PIN_LIMIT hit — the ceiling
     * only guards NEW pins).
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER},
     *                      {@code NOT_ADMIN} (GROUP non-admin),
     *                      {@code MESSAGE_NOT_FOUND},
     *                      {@code VALIDATION_FAILED} (message belongs to
     *                      another conversation), {@code PIN_LIMIT}
     */
    @Transactional
    public void pin(UUID userId, UUID conversationId, PinRequest request) {
        if (request == null || request.messageId() == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "messageId is required");
        }
        UUID messageId = request.messageId();
        // Identity-first authorization stays first (no join on a foreign id);
        // the pinning user is then locked FOR KEY SHARE before the conversation
        // row so the pinned_messages user FK never inverts user -> conversation.
        ConversationMember member = requireMember(conversationId, userId);
        userRepository.lockForKeyShareSorted(List.of(userId));
        Conversation conversation = lockConversation(conversationId);
        member = requireMember(conversationId, userId);
        requirePinRole(conversation, member);
        requireConversationMessage(conversationId, messageId);

        if (!pinRepository.existsByConversation_IdAndMessage_Id(conversationId, messageId)) {
            if (pinRepository.countByConversation_Id(conversationId) >= MAX_PINS_PER_CONVERSATION) {
                throw ApiException.of(ApiErrorCode.PIN_LIMIT,
                        "At most " + MAX_PINS_PER_CONVERSATION + " pins per conversation");
            }
            pinRepository.insertIgnoringConflict(conversationId, messageId, userId);
            log.info("Message {} pinned in {} by {}", messageId, conversationId, userId);
        }
        eventPublisher.publishEvent(
                new PinUpdatedEvent(conversationId, new PinUpdatedPayload(messageId, true)));
    }

    /**
     * Removes the pin on {@code messageId}; idempotent — unpinning a
     * not-pinned message is a no-op still broadcast as
     * {@code PIN_UPDATED {pinned:false}} (truthful, receivers converge).
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER},
     *                      {@code NOT_ADMIN} (GROUP non-admin)
     */
    @Transactional
    public void unpin(UUID userId, UUID conversationId, UUID messageId) {
        ConversationMember member = requireMember(conversationId, userId);
        userRepository.lockForKeyShareSorted(List.of(userId));
        Conversation conversation = lockConversation(conversationId);
        requirePinRole(conversation, member);
        // Same-conversation check as pin() — without it a member could
        // inject a spurious PIN_UPDATED{pinned:false} for a foreign id.
        requireConversationMessage(conversationId, messageId);

        int removed = pinRepository.deleteOne(conversationId, messageId);
        if (removed > 0) {
            log.info("Message {} unpinned in {} by {}", messageId, conversationId, userId);
        }
        eventPublisher.publishEvent(
                new PinUpdatedEvent(conversationId, new PinUpdatedPayload(messageId, false)));
    }

    // ------------------------------------------------------------- internals

    /**
     * {@code FOR UPDATE} fetch — the lock that serializes pin/unpin per
     * conversation (see class doc); also doubles as the existence check and
     * the type source for the DIRECT/GROUP policy.
     */
    private Conversation lockConversation(UUID conversationId) {
        return conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
    }

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
    }

    /** DIRECT: any member; GROUP: ADMIN only (plan §10.2 authz column). */
    private static void requirePinRole(Conversation conversation, ConversationMember member) {
        if (conversation.getType() == Conversation.Type.GROUP
                && member.getRole() != ConversationMember.Role.ADMIN) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Group admin role required");
        }
    }

    /** The pinned target must live in THIS conversation — same rule as reply targets. */
    private void requireConversationMessage(UUID conversationId, UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        if (!conversationId.equals(message.getConversation().getId())) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "messageId belongs to a different conversation");
        }
        if (message.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.MESSAGE_DELETED, "Message was deleted");
        }
    }
}

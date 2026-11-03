package com.nexachat.message;

import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.dto.AttachmentResponse;
import com.nexachat.message.dto.MessagePageResponse;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.ReactionRow;
import com.nexachat.message.dto.ReactionView;
import com.nexachat.message.dto.ReadRequest;
import com.nexachat.message.dto.ReplyRefResponse;
import com.nexachat.message.dto.ReplyRefRow;
import com.nexachat.message.dto.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Message use cases — the single enforcement point for send authZ,
 * idempotency, cursor history, and the read watermark (plan §10.2 note: WS and
 * the REST fallback both come through here, logic is never duplicated).
 *
 * <p><b>Sequencing</b> (DDL ambiguity resolved): {@code messages.seq} is a
 * GLOBAL {@code BIGSERIAL} — the DB sequence assigns it at INSERT, so no
 * per-conversation counter or locking is needed. Concurrent senders simply
 * draw distinct seqs; commit order can interleave but within one conversation
 * seq order is still monotonic, and gaps are harmless (plan §9.3.1).
 * {@code conversations.last_message_*} is a denormalized pointer updated in
 * the same tx, never the seq source.
 *
 * <p><b>Idempotency</b>: {@code INSERT ... ON CONFLICT (sender_id,
 * client_message_id) DO NOTHING} — race-safe at the DB level (no
 * check-then-insert window); a dedup hit re-reads and re-broadcasts the
 * existing row so a retrying sender still gets its SENT signal.
 *
 * <p><b>Broadcast</b>: nothing is pushed inside the transaction — a Spring
 * event is published and {@link MessageBroadcastListener} fans out
 * AFTER_COMMIT (persist-before-broadcast, plan §11.3.1).
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** Client-facing page-size ceiling for history reads. */
    static final int MAX_PAGE_LIMIT = 100;
    static final int DEFAULT_PAGE_LIMIT = 30;

    /** Cap on {@code attachmentIds} per send (phase-09) — bounds the bind work per message. */
    static final int MAX_ATTACHMENTS_PER_MESSAGE = 10;

    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final MessageReactionRepository reactionRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final StorageService storageService;
    private final ApplicationEventPublisher eventPublisher;

    public MessageService(MessageRepository messageRepository,
                          MessageAttachmentRepository attachmentRepository,
                          MessageReactionRepository reactionRepository,
                          ConversationRepository conversationRepository,
                          ConversationMemberRepository memberRepository,
                          StorageService storageService,
                          ApplicationEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.reactionRepository = reactionRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.storageService = storageService;
        this.eventPublisher = eventPublisher;
    }

    /** Result of {@link #send}: the stored message plus whether this call created it. */
    public record SendResult(MessageResponse message, boolean created) {
    }

    /**
     * Sends a message into {@code conversationId} on behalf of {@code senderId}.
     *
     * <p>Retry semantics: the same {@code clientMessageId} re-sent to the same
     * conversation returns (and re-broadcasts) the already-stored row — receivers
     * dedup by message id client-side (plan §11.3.3). Reusing the key for a
     * DIFFERENT conversation is a client-contract violation →
     * {@code VALIDATION_FAILED}.
     *
     * @throws ApiException {@code NOT_MEMBER}, {@code VALIDATION_FAILED},
     *                      {@code MESSAGE_NOT_FOUND} (reply target),
     *                      {@code FILE_NOT_FOUND} (attachment id)
     */
    @Transactional
    public SendResult send(UUID senderId, UUID conversationId, SendMessageRequest request) {
        requireMember(conversationId, senderId);
        validatePayload(request);
        Message.Type type = parseType(request.type());
        List<UUID> attachmentIds = distinctIds(request.attachmentIds());
        validateAttachmentContract(type, attachmentIds);
        String content = normalizedContent(type, request.content());
        UUID replyToId = resolveReplyTo(conversationId, request.replyToId());

        int inserted = messageRepository.insertIgnoringDuplicateKey(
                conversationId, senderId, request.clientMessageId(), type.name(), content, replyToId);
        boolean created = inserted == 1;

        // The row is guaranteed to exist now: ours if inserted, the winner's if deduped.
        MessageRow row = messageRepository
                .findRowBySenderAndClientMessageId(senderId, request.clientMessageId())
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.INTERNAL_ERROR, "Message insert/read-back inconsistency"));

        if (!created && !conversationId.equals(row.conversationId())) {
            // The idempotency key already stored a message in ANOTHER
            // conversation — a client bug, not a retry.
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "clientMessageId already used in a different conversation");
        }

        if (created) {
            conversationRepository.advanceLastMessage(conversationId, row.id(), row.seq());
            // You have trivially "read" what you sent — keep your own unread at 0.
            memberRepository.advanceReadWatermark(conversationId, senderId, row.seq());
            bindAttachments(row.id(), senderId, type, attachmentIds);
            log.info("Message {} (seq={}) stored in {} by {}", row.id(), row.seq(), conversationId, senderId);
        }

        MessageResponse response = toResponse(row);
        // Broadcast on dedup too: the re-sent frame is how a retrying sender
        // gets its SENT signal; receivers dedup by message id (plan §11.3.3).
        eventPublisher.publishEvent(new MessageCreatedEvent(conversationId, response));
        return new SendResult(response, created);
    }

    /**
     * Cursor history (plan §10.3): newest-first page strictly older than
     * {@code beforeSeq}. The client reverses for display.
     */
    @Transactional(readOnly = true)
    public MessagePageResponse getHistory(UUID userId, UUID conversationId,
                                          Long beforeSeq, Long afterSeq, Integer limit) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        requireMember(conversationId, userId);
        if (beforeSeq != null && afterSeq != null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "beforeSeq and afterSeq are mutually exclusive");
        }

        int pageSize = normalizeLimit(limit);
        List<MessageRow> rows = afterSeq != null
                ? messageRepository.findRowsAfter(
                        conversationId, afterSeq, PageRequest.of(0, pageSize + 1))
                : messageRepository.findPageRows(
                        conversationId, beforeSeq, PageRequest.of(0, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<MessageRow> page = hasMore ? rows.subList(0, pageSize) : rows;

        Map<UUID, List<AttachmentResponse>> attachmentsByMessage = page.isEmpty()
                ? Map.of()
                : attachmentRepository.findByMessage_IdIn(
                                page.stream().map(MessageRow::id).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                a -> a.getMessage().getId(),
                                Collectors.mapping(this::toAttachmentResponse, Collectors.toList())));

        // Phase-10 hydration, same one-IN-query-per-facet style: reaction
        // summaries (viewer = the reader) and reply-target previews.
        Map<UUID, List<ReactionView>> reactionsByMessage =
                reactionsByMessage(page.stream().map(MessageRow::id).toList(), userId);
        Map<UUID, ReplyRefResponse> replyRefsById = replyRefsById(
                page.stream().map(MessageRow::replyToId).filter(Objects::nonNull).toList());

        List<MessageResponse> items = page.stream()
                .map(row -> toResponse(row,
                        attachmentsByMessage.getOrDefault(row.id(), List.of()),
                        reactionsByMessage.getOrDefault(row.id(), List.of()),
                        row.replyToId() == null ? null : replyRefsById.get(row.replyToId())))
                .toList();
        Long nextBeforeSeq = (afterSeq != null || items.isEmpty())
                ? null : items.get(items.size() - 1).seq();
        return new MessagePageResponse(items, nextBeforeSeq, hasMore);
    }

    /**
     * Server-generated {@code SYSTEM} line for group membership events
     * (add/remove/leave/rename — phase-08). This is the ONLY way a SYSTEM row
     * is written; {@link #send} rejects the type so clients cannot forge one.
     *
     * <p>Sender choice: {@code messages.sender_id} is {@code NOT NULL} and no
     * dedicated system account exists, so the ACTING user authors the line
     * (the admin for add/remove/rename, the leaver for "left", the creator for
     * "created"). Callers post BEFORE mutating that user's membership row so
     * the member guard below always holds inside the same transaction.
     *
     * <p>Shares the persist-before-broadcast mechanics of {@link #send}: the
     * server mints a fresh {@code clientMessageId} (the UNIQUE key is a NOT
     * NULL column, not a client contract here), the same idempotent insert
     * writes the row, {@code last_message_*} advances in-tx, and the
     * {@link MessageCreatedEvent} fans out AFTER COMMIT as
     * {@code MESSAGE_CREATED} with {@code type=SYSTEM}.
     *
     * @throws ApiException {@code NOT_MEMBER} (acting user is not a member —
     *                      system lines are always authored by a member),
     *                      {@code VALIDATION_FAILED} (blank content)
     */
    @Transactional
    public MessageResponse postSystemMessage(UUID actingUserId, UUID conversationId, String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "System message content must not be blank");
        }
        requireMember(conversationId, actingUserId);

        UUID clientMessageId = UUID.randomUUID();
        messageRepository.insertIgnoringDuplicateKey(
                conversationId, actingUserId, clientMessageId, Message.Type.SYSTEM.name(), content, null);
        MessageRow row = messageRepository
                .findRowBySenderAndClientMessageId(actingUserId, clientMessageId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.INTERNAL_ERROR, "System message insert/read-back inconsistency"));

        conversationRepository.advanceLastMessage(conversationId, row.id(), row.seq());
        memberRepository.advanceReadWatermark(conversationId, actingUserId, row.seq());
        log.info("System message {} (seq={}) stored in {} by {}", row.id(), row.seq(), conversationId, actingUserId);

        MessageResponse response = toResponse(row);
        eventPublisher.publishEvent(new MessageCreatedEvent(conversationId, response));
        return response;
    }

    /**
     * Advances the caller's read watermark to {@code upToSeq} (monotonic —
     * never regresses) and broadcasts {@code MESSAGE_READ} to the topic after
     * commit so partners render the "seen" receipt. The broadcast is emitted
     * even when the watermark did not move: a replayed frame is truthful and
     * idempotent for receivers.
     */
    @Transactional
    public void markRead(UUID userId, UUID conversationId, ReadRequest request) {
        if (request == null || request.upToSeq() == null || request.upToSeq() < 0) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "upToSeq must be a non-negative seq");
        }
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        requireMember(conversationId, userId);
        long upToSeq = request.upToSeq();
        memberRepository.advanceReadWatermark(conversationId, userId, upToSeq);
        eventPublisher.publishEvent(new MessageReadEvent(conversationId, userId, upToSeq));
    }

    // ------------------------------------------------------------- internals

    private void requireMember(UUID conversationId, UUID userId) {
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
    }

    private static void validatePayload(SendMessageRequest request) {
        if (request == null || request.clientMessageId() == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "clientMessageId is required");
        }
        if (request.type() == null || request.type().isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "type is required");
        }
    }

    private static Message.Type parseType(String raw) {
        final Message.Type type;
        try {
            type = Message.Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unsupported message type");
        }
        // SYSTEM is server-generated — clients may not forge it.
        if (type == Message.Type.SYSTEM) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "SYSTEM messages are server-generated");
        }
        return type;
    }

    private static String normalizedContent(Message.Type type, String content) {
        if (type == Message.Type.TEXT && (content == null || content.isBlank())) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "TEXT messages require content");
        }
        return content;
    }

    /**
     * A reply target must exist and live in the SAME conversation — quoting a
     * message across conversations is a client bug (and would leak its id).
     */
    private UUID resolveReplyTo(UUID conversationId, UUID replyToId) {
        if (replyToId == null) {
            return null;
        }
        Message target = messageRepository.findById(replyToId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Reply target not found"));
        if (!conversationId.equals(target.getConversation().getId())) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "replyToId belongs to a different conversation");
        }
        return replyToId;
    }

    private static List<UUID> distinctIds(List<UUID> attachmentIds) {
        if (attachmentIds == null) {
            return List.of();
        }
        return attachmentIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    /**
     * Static type contract (phase-09): TEXT carries no attachments; every
     * media type (IMAGE/VIDEO/AUDIO/FILE) carries at least one — a media
     * message with nothing to render is a client bug, not a state the API
     * stores. The list is capped so one send cannot fan out into unbounded
     * bind work. Runs BEFORE the insert so bad payloads fail fast.
     */
    private static void validateAttachmentContract(Message.Type type, List<UUID> attachmentIds) {
        if (type == Message.Type.TEXT) {
            if (!attachmentIds.isEmpty()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "TEXT messages do not carry attachments");
            }
            return;
        }
        // SYSTEM never reaches here — parseType already rejected it.
        if (attachmentIds.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    type.name() + " messages require attachmentIds");
        }
        if (attachmentIds.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Too many attachments (max " + MAX_ATTACHMENTS_PER_MESSAGE + ")");
        }
    }

    /**
     * Phase-09 binding: claims each uploaded attachment for the freshly
     * inserted message inside the same transaction. A claimable row must
     * exist, be owned by the sender, and still be unbound
     * ({@code message_id IS NULL} since V2) — anything else is a client error,
     * never silently dropped. The sniffed MIME must also agree with the
     * declared message type (a PDF cannot ride out as IMAGE).
     *
     * <p>Skipped on the dedup path: the original send already bound its rows
     * and {@link #toResponse} re-reads them.</p>
     */
    private void bindAttachments(UUID messageId, UUID senderId, Message.Type type,
                                 List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return;
        }
        List<MessageAttachment> found = attachmentRepository.findAllById(attachmentIds);
        if (found.size() != attachmentIds.size()) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND, "Attachment not found");
        }
        Message target = messageRepository.getReferenceById(messageId);
        for (MessageAttachment attachment : found) {
            if (!senderId.equals(attachment.getOwner().getId())) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Attachment is owned by another user");
            }
            if (attachment.getMessage() != null) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Attachment is already bound to a message");
            }
            if (!mimeMatchesType(type, attachment.getMimeType())) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Attachment content does not match message type " + type.name());
            }
            attachment.bindTo(target);
        }
    }

    /** IMAGE/VIDEO/AUDIO accept only their own sniffed family; FILE is the catch-all. */
    private static boolean mimeMatchesType(Message.Type type, String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return switch (type) {
            case IMAGE -> mimeType.startsWith("image/");
            case VIDEO -> mimeType.startsWith("video/");
            case AUDIO -> mimeType.startsWith("audio/");
            default -> true; // FILE accepts any whitelisted sniff
        };
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }

    /**
     * Single-row assembly for the send/system paths. The viewer for
     * {@code byMe} is the message's own sender — the REST send response goes
     * back to that user, and a fresh {@code MESSAGE_CREATED} broadcast carries
     * no reactions yet anyway.
     */
    MessageResponse toResponse(MessageRow row) {
        return toResponse(row, row.senderId());
    }

    /**
     * Single-row assembly for an arbitrary viewer (e.g. {@link PinService}
     * re-uses it for the pinned list — {@code byMe} must reflect the caller,
     * not the message author).
     */
    MessageResponse toResponse(MessageRow row, UUID viewerId) {
        List<AttachmentResponse> attachments = attachmentRepository
                .findByMessage_IdIn(List.of(row.id()))
                .stream()
                .map(this::toAttachmentResponse)
                .toList();
        List<ReactionView> reactions = reactionsByMessage(List.of(row.id()), viewerId)
                .getOrDefault(row.id(), List.of());
        ReplyRefResponse replyTo = row.replyToId() == null
                ? null
                : replyRefsById(List.of(row.replyToId())).get(row.replyToId());
        return toResponse(row, attachments, reactions, replyTo);
    }

    private static MessageResponse toResponse(MessageRow row,
                                              List<AttachmentResponse> attachments,
                                              List<ReactionView> reactions,
                                              ReplyRefResponse replyTo) {
        return new MessageResponse(
                row.id(), row.conversationId(), row.seq(), row.senderId(), row.senderDisplayName(),
                row.type().name(), row.content(), row.clientMessageId(), row.replyToId(),
                replyTo, attachments, reactions, row.createdAt(), row.editedAt(), row.deletedAt());
    }

    /**
     * One IN query → per-message emoji summaries, ordered by emoji for a
     * stable wire array. {@code byMe} is computed against {@code viewerId}.
     */
    private Map<UUID, List<ReactionView>> reactionsByMessage(Collection<UUID> messageIds,
                                                           UUID viewerId) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, List<UUID>>> grouped = new LinkedHashMap<>();
        for (ReactionRow row : reactionRepository.findRowsByMessageIdIn(messageIds)) {
            grouped.computeIfAbsent(row.messageId(), k -> new TreeMap<>())
                    .computeIfAbsent(row.emoji(), k -> new ArrayList<>())
                    .add(row.userId());
        }
        Map<UUID, List<ReactionView>> result = new LinkedHashMap<>();
        grouped.forEach((messageId, byEmoji) -> result.put(messageId,
                byEmoji.entrySet().stream()
                        .map(e -> new ReactionView(
                                e.getKey(), e.getValue().size(), e.getValue().contains(viewerId)))
                        .toList()));
        return result;
    }

    /**
     * One IN query → reply-target previews. A soft-deleted target keeps its
     * id and sender but loses its content ({@code deleted=true} renders the
     * "Tin nhắn đã bị xóa" placeholder — plan §9.3.6).
     */
    private Map<UUID, ReplyRefResponse> replyRefsById(Collection<UUID> replyToIds) {
        if (replyToIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ReplyRefResponse> result = new LinkedHashMap<>();
        for (ReplyRefRow row : messageRepository.findReplyRefsByIdIn(replyToIds)) {
            boolean deleted = row.deletedAt() != null;
            result.put(row.id(), new ReplyRefResponse(
                    row.id(), row.senderId(), row.senderDisplayName(), row.type().name(),
                    deleted ? null : row.content(), deleted));
        }
        return result;
    }

    private AttachmentResponse toAttachmentResponse(MessageAttachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                storageService.resolveUrl(attachment.getStorageKey()),
                attachment.getOriginalName(),
                attachment.getMimeType(),
                attachment.getSizeBytes(),
                attachment.getWidth(),
                attachment.getHeight(),
                attachment.getDurationMs());
    }
}

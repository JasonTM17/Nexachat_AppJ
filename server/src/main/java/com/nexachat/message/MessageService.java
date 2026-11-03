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
import com.nexachat.message.dto.ReadRequest;
import com.nexachat.message.dto.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final StorageService storageService;
    private final ApplicationEventPublisher eventPublisher;

    public MessageService(MessageRepository messageRepository,
                          MessageAttachmentRepository attachmentRepository,
                          ConversationRepository conversationRepository,
                          ConversationMemberRepository memberRepository,
                          StorageService storageService,
                          ApplicationEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
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
            validateAttachmentBindings(row.id(), request.attachmentIds());
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

        List<MessageResponse> items = page.stream()
                .map(row -> toResponse(row, attachmentsByMessage.getOrDefault(row.id(), List.of())))
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

    /**
     * Phase-8 binding hook: every attachment id must resolve AND either be
     * unbound or already bound to THIS message. Because {@code message_id} is
     * NOT NULL, unbound rows cannot exist yet — so until the upload flow lands,
     * any non-empty list resolves to rows bound elsewhere and is rejected as a
     * client error rather than silently dropped.
     */
    private void validateAttachmentBindings(UUID messageId, List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        List<UUID> distinctIds = attachmentIds.stream().filter(Objects::nonNull).distinct().toList();
        List<MessageAttachment> found = attachmentRepository.findAllById(distinctIds);
        if (found.size() != distinctIds.size()) {
            throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND, "Attachment not found");
        }
        for (MessageAttachment attachment : found) {
            if (!messageId.equals(attachment.getMessage().getId())) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Attachment is already bound to another message");
            }
        }
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

    MessageResponse toResponse(MessageRow row) {
        List<AttachmentResponse> attachments = attachmentRepository
                .findByMessage_IdIn(List.of(row.id()))
                .stream()
                .map(this::toAttachmentResponse)
                .toList();
        return toResponse(row, attachments);
    }

    private static MessageResponse toResponse(MessageRow row, List<AttachmentResponse> attachments) {
        return new MessageResponse(
                row.id(), row.conversationId(), row.seq(), row.senderId(), row.senderDisplayName(),
                row.type().name(), row.content(), row.clientMessageId(), row.replyToId(),
                attachments, row.createdAt(), row.editedAt());
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

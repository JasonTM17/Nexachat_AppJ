package com.nexachat.message;

import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.ConversationUpdatedEvent;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.conversation.dto.MemberFlagsRow;
import com.nexachat.message.dto.AttachmentResponse;
import com.nexachat.message.dto.EditMessageRequest;
import com.nexachat.message.dto.MessagePageResponse;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.MessageDeletedPayload;
import com.nexachat.message.dto.MessageMentionRequest;
import com.nexachat.message.dto.MessageMentionResponse;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.MessageSearchProjection;
import com.nexachat.message.dto.PinUpdatedPayload;
import com.nexachat.message.dto.ReactionEventId;
import com.nexachat.message.dto.ReactionRow;
import com.nexachat.message.dto.ReactionSlice;
import com.nexachat.message.dto.ReactionUpdatedPayload;
import com.nexachat.message.dto.ReactionView;
import com.nexachat.message.dto.ReadRequest;
import com.nexachat.message.dto.ReplyRefResponse;
import com.nexachat.message.dto.ReplyRefRow;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.message.dto.SyncRequest;
import com.nexachat.message.dto.SyncResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    static final int MAX_CONTENT_LENGTH = 10_000;

    private final MessageRepository messageRepository;
    private final MessageMentionRepository mentionRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final MessageReactionRepository reactionRepository;
    private final ReactionEventRepository reactionEventRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final PinnedMessageRepository pinnedMessageRepository;
    private final StorageService storageService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final SyncProperties syncProperties;
    private final MessageRecallProperties recallProperties;

    @Autowired
    public MessageService(MessageRepository messageRepository,
                          MessageMentionRepository mentionRepository,
                          MessageAttachmentRepository attachmentRepository,
                          MessageReactionRepository reactionRepository,
                          ReactionEventRepository reactionEventRepository,
                          ConversationRepository conversationRepository,
                          ConversationMemberRepository memberRepository,
                          PinnedMessageRepository pinnedMessageRepository,
                          StorageService storageService,
                          ApplicationEventPublisher eventPublisher,
                          SyncProperties syncProperties,
                          MessageRecallProperties recallProperties) {
        this(messageRepository, mentionRepository, attachmentRepository, reactionRepository, reactionEventRepository,
                conversationRepository, memberRepository, pinnedMessageRepository, storageService,
                eventPublisher, syncProperties, Clock.systemUTC(), recallProperties);
    }

    /** Compatibility constructor for isolated service tests that do not exercise mentions. */
    public MessageService(MessageRepository messageRepository,
                          MessageAttachmentRepository attachmentRepository,
                          MessageReactionRepository reactionRepository,
                          ReactionEventRepository reactionEventRepository,
                          ConversationRepository conversationRepository,
                          ConversationMemberRepository memberRepository,
                          PinnedMessageRepository pinnedMessageRepository,
                          StorageService storageService,
                          ApplicationEventPublisher eventPublisher,
                          SyncProperties syncProperties) {
        this(messageRepository, null, attachmentRepository, reactionRepository, reactionEventRepository,
                conversationRepository, memberRepository, pinnedMessageRepository, storageService,
                eventPublisher, syncProperties, Clock.systemUTC(), new MessageRecallProperties());
    }

    MessageService(MessageRepository messageRepository,
                   MessageAttachmentRepository attachmentRepository,
                   MessageReactionRepository reactionRepository,
                   ReactionEventRepository reactionEventRepository,
                   ConversationRepository conversationRepository,
                   ConversationMemberRepository memberRepository,
                   PinnedMessageRepository pinnedMessageRepository,
                   StorageService storageService,
                   ApplicationEventPublisher eventPublisher,
                   SyncProperties syncProperties,
                   Clock clock) {
        this(messageRepository, null, attachmentRepository, reactionRepository, reactionEventRepository,
                conversationRepository, memberRepository, pinnedMessageRepository, storageService,
                eventPublisher, syncProperties, clock, new MessageRecallProperties());
    }

    MessageService(MessageRepository messageRepository,
                   MessageMentionRepository mentionRepository,
                   MessageAttachmentRepository attachmentRepository,
                   MessageReactionRepository reactionRepository,
                   ReactionEventRepository reactionEventRepository,
                   ConversationRepository conversationRepository,
                   ConversationMemberRepository memberRepository,
                   PinnedMessageRepository pinnedMessageRepository,
                   StorageService storageService,
                   ApplicationEventPublisher eventPublisher,
                   SyncProperties syncProperties,
                   Clock clock) {
        this(messageRepository, mentionRepository, attachmentRepository, reactionRepository, reactionEventRepository,
                conversationRepository, memberRepository, pinnedMessageRepository, storageService,
                eventPublisher, syncProperties, clock, new MessageRecallProperties());
    }

    MessageService(MessageRepository messageRepository,
                   MessageMentionRepository mentionRepository,
                   MessageAttachmentRepository attachmentRepository,
                   MessageReactionRepository reactionRepository,
                   ReactionEventRepository reactionEventRepository,
                   ConversationRepository conversationRepository,
                   ConversationMemberRepository memberRepository,
                   PinnedMessageRepository pinnedMessageRepository,
                   StorageService storageService,
                   ApplicationEventPublisher eventPublisher,
                   SyncProperties syncProperties,
                   Clock clock,
                   MessageRecallProperties recallProperties) {
        this.messageRepository = messageRepository;
        this.mentionRepository = mentionRepository;
        this.attachmentRepository = attachmentRepository;
        this.reactionRepository = reactionRepository;
        this.reactionEventRepository = reactionEventRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.pinnedMessageRepository = pinnedMessageRepository;
        this.storageService = storageService;
        this.eventPublisher = eventPublisher;
        this.syncProperties = syncProperties;
        this.clock = clock;
        this.recallProperties = recallProperties;
    }

    /** Result of {@link #send}: the stored message plus whether this call created it. */
    public record SendResult(MessageResponse message, boolean created) {
    }

    /**
     * Sends a message into {@code conversationId} on behalf of {@code senderId}.
     *
     * <p>Retry semantics: the same {@code clientMessageId} re-sent to the same
     * conversation returns (and re-broadcasts) the already-stored row; receivers
     * deduplicate by message id client-side. Reusing the key for a DIFFERENT
     * conversation is a client-contract violation →
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
        MentionState mentionState = validateMentions(conversationId, senderId, type, content,
                request.mentions(), Boolean.TRUE.equals(request.mentionAll()));
        UUID replyToId = resolveReplyTo(conversationId, request.replyToId());

        int inserted = mentionState.mentionAll()
                ? messageRepository.insertIgnoringDuplicateKeyWithMentionAll(
                        conversationId, senderId, request.clientMessageId(), type.name(), content,
                        replyToId, true)
                : messageRepository.insertIgnoringDuplicateKey(
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
            if (!mentionState.mentions().isEmpty()) {
                requireMentionRepository().replace(row.id(), conversationId, mentionState.mentions());
            }
            conversationRepository.advanceLastMessage(conversationId, row.id(), row.seq());
            // You have trivially "read" what you sent — keep your own unread at 0.
            memberRepository.advanceReadWatermark(conversationId, senderId, row.seq());
            bindAttachments(row.id(), senderId, type, attachmentIds);
            log.info("Message {} (seq={}) stored in {} by {}", row.id(), row.seq(), conversationId, senderId);
        }

        MessageResponse response = toResponse(row);
        // Broadcast on dedup too: the re-sent frame is how a retrying sender
        // gets its SENT signal; receivers deduplicate by message id.
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

        List<MessageResponse> items = hydrateResponses(page, userId);
        Long nextBeforeSeq = (afterSeq != null || items.isEmpty())
                ? null : items.get(items.size() - 1).seq();
        return new MessagePageResponse(items, nextBeforeSeq, hasMore);
    }

    @Transactional
    public MessageResponse edit(UUID userId, UUID conversationId, UUID messageId, String content) {
        return edit(userId, conversationId, messageId, new EditMessageRequest(content));
    }

    @Transactional
    public MessageResponse edit(UUID userId, UUID conversationId, UUID messageId,
                                EditMessageRequest request) {
        requireMember(conversationId, userId);
        Message message = messageRepository.findByIdForUpdate(messageId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        requireMessageConversation(message, conversationId);
        requireMessageAuthor(message, userId);
        if (message.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.MESSAGE_DELETED, "Message was deleted");
        }
        if (message.getType() != Message.Type.TEXT) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Only TEXT messages can be edited");
        }
        if (request == null || request.content() == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "content is required");
        }
        String normalized = request.content().strip();
        if (normalized.isEmpty() || normalized.length() > MAX_CONTENT_LENGTH
                || normalized.indexOf('\0') >= 0) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "content must contain 1 to " + MAX_CONTENT_LENGTH + " characters");
        }

        List<MessageMentionResponse> existingMentions = mentionRepository == null
                ? List.of() : mentionRepository.findByMessageId(messageId);
        List<MessageMentionRequest> requestedMentions = request.mentions() == null
                ? existingMentions.stream()
                        .map(mention -> new MessageMentionRequest(
                                mention.userId(), mention.offset(), mention.length()))
                        .toList()
                : request.mentions();
        boolean mentionAll = request.mentionAll() == null
                ? message.isMentionAll() : request.mentionAll();
        MentionState mentionState = validateMentions(conversationId, userId, message.getType(),
                normalized, requestedMentions, mentionAll);
        boolean contentChanged = !normalized.equals(message.getContent());
        boolean mentionsChanged = !mentionState.mentions().equals(existingMentions)
                || mentionState.mentionAll() != message.isMentionAll();

        MessageRow row;
        if (!contentChanged && !mentionsChanged) {
            row = messageRepository.findRowById(messageId)
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
            return toResponse(row, userId);
        }

        java.time.Instant editedAt = java.time.Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (message.getEditedAt() != null && !editedAt.isAfter(message.getEditedAt())) {
            editedAt = message.getEditedAt().plus(1, ChronoUnit.MICROS);
        }
        if (contentChanged) {
            message.editContent(normalized, editedAt);
        } else {
            message.editContent(message.getContent(), editedAt);
        }
        message.setMentionAll(mentionState.mentionAll());
        if (mentionsChanged) {
            requireMentionRepository().replace(messageId, conversationId, mentionState.mentions());
        }
        row = messageRepository.findRowById(messageId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        MessageResponse response = toResponse(row, userId);
        eventPublisher.publishEvent(new MessageUpdatedEvent(conversationId, response));
        if (isLastMessage(conversationId, messageId)) {
            publishLastMessageChanged(conversationId, "MESSAGE_EDITED");
        }
        return response;
    }

    @Transactional
    public void delete(UUID userId, UUID conversationId, UUID messageId) {
        requireMember(conversationId, userId);
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        Message message = messageRepository.findByIdForUpdate(messageId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        requireMessageConversation(message, conversationId);

        boolean groupAdmin = conversation.getType() == Conversation.Type.GROUP
                && memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                        .map(actor -> actor.getRole() == ConversationMember.Role.ADMIN)
                        .orElse(false);
        if (!groupAdmin) {
            requireMessageAuthor(message, userId);
        }
        if (message.getDeletedAt() != null) {
            return;
        }

        Instant recalledAt = clock.instant();
        if (!groupAdmin && !recallProperties.isWithinRecallWindow(message.getCreatedAt(), recalledAt)) {
            throw ApiException.of(ApiErrorCode.RECALL_WINDOW_EXPIRED, "Message recall window has expired");
        }

        boolean wasLastMessage = isLastMessage(conversationId, messageId);
        Instant deletedAt = recalledAt.truncatedTo(ChronoUnit.MICROS);
        message.softDelete(deletedAt);
        if (mentionRepository != null) {
            mentionRepository.deleteByMessageId(messageId);
        }
        reactionRepository.deleteByMessageId(messageId);
        if (pinnedMessageRepository.deleteOne(conversationId, messageId) > 0) {
            eventPublisher.publishEvent(new PinUpdatedEvent(
                    conversationId, new PinUpdatedPayload(messageId, false)));
        }
        MessageRow row = messageRepository.findRowById(messageId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        eventPublisher.publishEvent(new MessageDeletedEvent(conversationId,
                new MessageDeletedPayload(messageId, row.deletedAt())));
        if (wasLastMessage) {
            publishLastMessageChanged(conversationId, "MESSAGE_DELETED");
        }
    }

    @Transactional(readOnly = true)
    public MessagePageResponse search(UUID userId, UUID conversationId, String query,
                                      Integer limit, Long beforeSeq) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        requireMember(conversationId, userId);
        if (query == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "q is required");
        }
        String normalized = query.strip();
        if (normalized.length() < 2 || normalized.length() > 100) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "q must contain 2 to 100 characters");
        }
        int pageSize = normalizeSearchLimit(limit);
        String pattern = "%" + escapeLike(normalized) + "%";
        List<MessageRow> rows = messageRepository.searchTextRows(
                        conversationId, pattern, beforeSeq, PageRequest.of(0, pageSize + 1)).stream()
                .map(MessageService::toMessageRow)
                .toList();
        boolean hasMore = rows.size() > pageSize;
        List<MessageRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<MessageResponse> items = hydrateResponses(page, userId);
        Long nextBeforeSeq = items.isEmpty() ? null : items.get(items.size() - 1).seq();
        return new MessagePageResponse(items, nextBeforeSeq, hasMore);
    }

    /** Three-argument convenience form: a SYSTEM line with no member subject. */
    @Transactional
    public MessageResponse postSystemMessage(UUID actingUserId, UUID conversationId, String content) {
        return postSystemMessage(actingUserId, conversationId, content, null);
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
     * @param subjectUserId the member the event is about — the removed member
     *                      or the member whose role changed — or {@code null}
     *                      when the event has no single member subject
     * @throws ApiException {@code NOT_MEMBER} (acting user is not a member —
     *                      system lines are always authored by a member),
     *                      {@code VALIDATION_FAILED} (blank content)
     */
    @Transactional
    public MessageResponse postSystemMessage(UUID actingUserId, UUID conversationId, String content,
                                             UUID subjectUserId) {
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
        eventPublisher.publishEvent(new MessageCreatedEvent(conversationId, response, subjectUserId));
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
        long latestSeq = messageRepository.findMaxSeqByConversationId(conversationId);
        if (upToSeq > latestSeq) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "upToSeq cannot exceed the latest message sequence");
        }
        memberRepository.advanceReadWatermark(conversationId, userId, upToSeq);
        // Reading implies delivery: a read receipt without a delivered receipt
        // would render an inconsistent two-tick state to the sender.
        memberRepository.advanceDeliveredWatermark(conversationId, userId, upToSeq);
        long persistedWatermark = memberRepository.findReadWatermark(conversationId, userId);
        eventPublisher.publishEvent(new MessageReadEvent(conversationId, userId, persistedWatermark));
    }

    /**
     * Delivery-watermark advance: advances {@code last_delivered_seq} monotonically
     * and broadcasts {@code MESSAGE_DELIVERED} to the topic after commit so senders
     * render 2 gray ticks ("Đã nhận" / DELIVERED).
     */
    @Transactional
    public void markDelivered(UUID conversationId, UUID userId, Long upToSeq) {
        if (upToSeq == null || upToSeq < 0) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "upToSeq must be a non-negative seq");
        }
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        requireMember(conversationId, userId);
        long latestSeq = messageRepository.findMaxSeqByConversationId(conversationId);
        if (upToSeq > latestSeq) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "upToSeq cannot exceed the latest message sequence");
        }
        memberRepository.advanceDeliveredWatermark(conversationId, userId, upToSeq);
        long persistedWatermark = memberRepository.findDeliveredWatermark(conversationId, userId);
        eventPublisher.publishEvent(new MessageDeliveredEvent(conversationId, userId, persistedWatermark));
    }

    /**
     * Batch delta sync: queries new messages, edited messages, deleted message IDs,
     * and reaction updates across multiple conversation watermarks in a single request.
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SyncResponse syncDelta(UUID userId, SyncRequest request) {
        if (request == null || request.watermarks() == null || request.watermarks().isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "watermarks list must not be empty");
        }
        int limit = request.limitPerConv() != null ? Math.max(1, Math.min(request.limitPerConv(), 100)) : 50;
        java.time.Instant since = request.since();
        // Captured BEFORE the queries: a row committed after its query ran but
        // before a later timestamp would be skipped by the next delta request.
        java.time.Instant requestTime = clock.instant();

        List<SyncRequest.ConversationWatermark> watermarks = request.watermarks();
        if (watermarks.size() > 100) {
            log.warn("Clamping watermarks list from {} to 100 items for user {}", watermarks.size(), userId);
            watermarks = watermarks.subList(0, 100);
        }

        // The caller's own mute/archive flags, batched once for the whole request
        // (no N+1) so a preference changed on another device is reflected here.
        Map<UUID, MemberFlagsRow> flagsByConversation = new LinkedHashMap<>();
        for (MemberFlagsRow flags : memberRepository.findMemberFlags(
                userId, watermarks.stream()
                        .map(SyncRequest.ConversationWatermark::conversationId)
                        .toList())) {
            flagsByConversation.put(flags.conversationId(), flags);
        }

        List<SyncResponse.ConversationDelta> deltas = new ArrayList<>();
        for (SyncRequest.ConversationWatermark wm : watermarks) {
            UUID conversationId = wm.conversationId();
            long lastSeq = wm.lastSeq();

            if (!conversationRepository.existsById(conversationId)
                    || !memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
                continue;
            }

            // 1. New messages after lastSeq
            List<MessageRow> newRows = messageRepository.findRowsAfter(
                    conversationId, lastSeq, PageRequest.of(0, limit + 1));
            boolean hasMore = newRows.size() > limit;
            List<MessageRow> pageRows = hasMore ? newRows.subList(0, limit) : newRows;
            List<MessageResponse> newMessages = hydrateResponses(pageRows, userId);

            // 2. Edited messages since timestamp (with seq <= lastSeq)
            List<MessageResponse> editedMessages = List.of();
            if (since != null) {
                List<MessageRow> editedRows = messageRepository.findEditedRowsSince(conversationId, lastSeq, since);
                if (editedRows.size() > limit) {
                    editedRows = editedRows.subList(0, limit);
                }
                editedMessages = hydrateResponses(editedRows, userId);
            }

            // 3. Deleted message IDs since timestamp (with seq <= lastSeq)
            List<UUID> deletedMessageIds = List.of();
            if (since != null) {
                List<UUID> allDeleted = messageRepository.findDeletedMessageIdsSince(conversationId, lastSeq, since);
                deletedMessageIds = allDeleted.size() > limit ? allDeleted.subList(0, limit) : allDeleted;
            }

            // 4. Reactions updated since timestamp — derived from the append-only
            //    event log so removals are visible, bounded per conversation and
            //    resumed by an event-id cursor when the page is truncated.
            List<ReactionUpdatedPayload> reactionUpdates = List.of();
            Long reactionCursor = null;
            boolean reactionsFullRefresh = false;
            if (since != null) {
                if (since.isBefore(clock.instant()
                        .minus(syncProperties.getReactionRetentionDays(), ChronoUnit.DAYS))) {
                    // Events older than the retention window may already be purged,
                    // so a partial delta could silently miss changes.
                    reactionsFullRefresh = true;
                } else {
                    ReactionDelta reactionDelta = reactionUpdates(conversationId, since, wm.reactionCursor());
                    reactionUpdates = reactionDelta.updates();
                    reactionCursor = reactionDelta.cursor();
                    if (reactionDelta.truncated()) {
                        hasMore = true;
                    }
                }
            }

            // 5. Max seq
            long maxSeq = messageRepository.findMaxSeqByConversationId(conversationId);

            // 6. The caller's own mute/archive/pin flags; a mute whose instant
            //    has passed is reported as not muted (lazy expiry, no sweep job).
            MemberFlagsRow flags = flagsByConversation.get(conversationId);
            boolean muted = flags != null && flags.isMutedAt(requestTime);
            java.time.Instant mutedUntil = flags == null ? null : flags.effectiveMutedUntil(requestTime);
            boolean archived = flags != null && flags.archived();
            boolean pinned = flags != null && flags.pinned();
            java.time.Instant pinnedAt = pinned ? flags.pinnedAt() : null;

            deltas.add(new SyncResponse.ConversationDelta(
                    conversationId,
                    newMessages,
                    editedMessages,
                    deletedMessageIds,
                    reactionUpdates,
                    maxSeq,
                    hasMore,
                    reactionCursor,
                    reactionsFullRefresh,
                    muted,
                    mutedUntil,
                    archived,
                    pinned,
                    pinnedAt
            ));
        }

        return new SyncResponse(deltas, requestTime);
    }

    /**
     * One bounded page of reaction updates for a conversation. The page is cut
     * at {@code maxReactionUpdates} events (so at most that many distinct
     * message ids are returned); when the page is full the cursor is the last
     * event id consumed and the caller must resume from it.
     */
    private ReactionDelta reactionUpdates(UUID conversationId, java.time.Instant since, Long cursor) {
        int cap = Math.max(1, syncProperties.getMaxReactionUpdates());
        // A separate statement after this lock gets a fresh READ COMMITTED
        // snapshot if it waited for a reaction transaction to commit.
        reactionEventRepository.lockConversationEvents(conversationId);
        List<ReactionEventId> events = reactionEventRepository.findEventIdsSince(
                conversationId, since, cursor, cap + 1);
        boolean truncated = events.size() > cap;
        List<ReactionEventId> page = truncated ? events.subList(0, cap) : events;
        Long nextCursor = truncated && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
        List<UUID> messageIds = page.stream()
                .map(ReactionEventId::getMessageId)
                .distinct()
                .toList();
        if (messageIds.isEmpty()) {
            return new ReactionDelta(List.of(), nextCursor, truncated);
        }
        Map<UUID, Map<String, List<UUID>>> grouped = new LinkedHashMap<>();
        for (ReactionRow row : reactionRepository.findRowsByMessageIdIn(messageIds)) {
            grouped.computeIfAbsent(row.messageId(), k -> new java.util.TreeMap<>())
                    .computeIfAbsent(row.emoji(), k -> new ArrayList<>())
                    .add(row.userId());
        }
        List<ReactionUpdatedPayload> payloads = new ArrayList<>();
        for (UUID messageId : messageIds) {
            Map<String, List<UUID>> byEmoji = grouped.getOrDefault(messageId, Map.of());
            List<ReactionSlice> slices = byEmoji.entrySet().stream()
                    .map(e -> new ReactionSlice(e.getKey(), e.getValue().size(), List.copyOf(e.getValue())))
                    .toList();
            payloads.add(new ReactionUpdatedPayload(messageId, slices));
        }
        return new ReactionDelta(payloads, nextCursor, truncated);
    }

    /** Reaction-delta page result: payloads, resume cursor, truncation flag. */
    private record ReactionDelta(List<ReactionUpdatedPayload> updates, Long cursor, boolean truncated) {
    }

    // ------------------------------------------------------------- internals

    private void requireMember(UUID conversationId, UUID userId) {
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
    }

    private static void requireMessageConversation(Message message, UUID conversationId) {
        if (!conversationId.equals(message.getConversation().getId())) {
            throw ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found");
        }
    }

    private static void requireMessageAuthor(Message message, UUID userId) {
        if (!userId.equals(message.getSender().getId())) {
            throw ApiException.of(ApiErrorCode.NOT_MESSAGE_AUTHOR, "Only the message author can change it");
        }
    }

    private boolean isLastMessage(UUID conversationId, UUID messageId) {
        return conversationRepository.findById(conversationId)
                .map(conversation -> messageId.equals(conversation.getLastMessageId()))
                .orElse(false);
    }

    private void publishLastMessageChanged(UUID conversationId, String change) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        eventPublisher.publishEvent(new ConversationUpdatedEvent(conversationId,
                new ConversationUpdatePayload(conversationId, conversation.getTitle(),
                        conversation.getAvatarUrl(), change, memberRepository.findMembers(conversationId)),
                List.of()));
    }

    private static int normalizeSearchLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1 || limit > 50) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "limit must be between 1 and 50");
        }
        return limit;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static MessageRow toMessageRow(MessageSearchProjection row) {
        return new MessageRow(row.getId(), row.getConversationId(), row.getSeq(), row.getSenderId(),
                row.getSenderDisplayName(), Message.Type.valueOf(row.getType()), row.getContent(),
                row.getClientMessageId(), row.getReplyToId(), row.getCreatedAt(),
                row.getEditedAt(), row.getDeletedAt(), Boolean.TRUE.equals(row.getMentionAll()));
    }

    private record MentionRange(int start, int end, MessageMentionResponse mention) {
    }

    private record MentionState(List<MessageMentionResponse> mentions, boolean mentionAll) {
    }

    private MentionState validateMentions(UUID conversationId, UUID actorId, Message.Type type,
                                          String content, List<MessageMentionRequest> rawMentions,
                                          boolean mentionAll) {
        List<MessageMentionRequest> requested = rawMentions == null ? List.of() : rawMentions;
        if (requested.size() > 20) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "At most 20 members can be mentioned");
        }
        if (requested.isEmpty() && !mentionAll) {
            return new MentionState(List.of(), false);
        }
        if (type != Message.Type.TEXT) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Mentions are supported on TEXT messages only");
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Mentions are supported in groups only");
        }
        if (mentionAll) {
            ConversationMember actor = memberRepository.findByConversation_IdAndUser_Id(conversationId, actorId)
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a conversation member"));
            if (actor.getRole() != ConversationMember.Role.ADMIN) {
                throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Only group admins can mention all members");
            }
        }

        Set<UUID> currentMemberIds = requested.isEmpty()
                ? Set.of() : new HashSet<>(memberRepository.findMemberUserIds(conversationId));
        Set<UUID> targetIds = new HashSet<>();
        List<MentionRange> ranges = new ArrayList<>();
        for (MessageMentionRequest request : requested) {
            if (request == null || request.userId() == null || request.offset() == null
                    || request.length() == null || request.offset() < 0 || request.length() <= 0) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Mention range is invalid");
            }
            long endLong = (long) request.offset() + request.length();
            if (content == null || endLong > content.length()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Mention range is outside message content");
            }
            int end = (int) endLong;
            if (!isCodePointBoundary(content, request.offset()) || !isCodePointBoundary(content, end)) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Mention range cannot split a Unicode surrogate pair");
            }
            if (!targetIds.add(request.userId())) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "A member can be mentioned only once");
            }
            if (!currentMemberIds.contains(request.userId())) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Mention target must be a current group member");
            }
            ranges.add(new MentionRange(request.offset(), end,
                    new MessageMentionResponse(request.userId(), request.offset(), request.length())));
        }

        ranges.sort(Comparator.comparingInt(MentionRange::start));
        for (int index = 1; index < ranges.size(); index++) {
            if (ranges.get(index).start() < ranges.get(index - 1).end()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Mention ranges cannot overlap");
            }
        }
        List<MessageMentionResponse> mentions = ranges.stream().map(MentionRange::mention).toList();
        return new MentionState(mentions, mentionAll);
    }

    private static boolean isCodePointBoundary(String content, int index) {
        return index == 0 || index == content.length()
                || !(Character.isHighSurrogate(content.charAt(index - 1))
                && Character.isLowSurrogate(content.charAt(index)));
    }

    private MessageMentionRepository requireMentionRepository() {
        if (mentionRepository == null) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Mention storage is unavailable");
        }
        return mentionRepository;
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
        if (content != null && (content.length() > MAX_CONTENT_LENGTH || content.indexOf('\0') >= 0)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Message content must be at most " + MAX_CONTENT_LENGTH + " characters");
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
        return hydrateResponses(List.of(row), viewerId).get(0);
    }

    /** Batch hydration shared by history and pinned-message read models. */
    List<MessageResponse> toResponses(List<MessageRow> rows, UUID viewerId) {
        return hydrateResponses(rows, viewerId);
    }

    private List<MessageResponse> hydrateResponses(List<MessageRow> rows, UUID viewerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> messageIds = rows.stream().map(MessageRow::id).toList();
        Map<UUID, List<AttachmentResponse>> attachmentsByMessage =
                attachmentRepository.findByMessage_IdIn(messageIds).stream()
                        .collect(Collectors.groupingBy(
                                attachment -> attachment.getMessage().getId(),
                                Collectors.mapping(this::toAttachmentResponse, Collectors.toList())));
        Map<UUID, List<ReactionView>> reactionsByMessage = reactionsByMessage(messageIds, viewerId);
        Map<UUID, List<MessageMentionResponse>> mentionsByMessage = mentionRepository == null
                ? Map.of() : mentionRepository.findByMessageIds(messageIds);
        Map<UUID, ReplyRefResponse> replyRefsById = replyRefsById(rows.stream()
                .map(MessageRow::replyToId)
                .filter(Objects::nonNull)
                .toList());

        return rows.stream()
                .map(row -> toResponse(row,
                        attachmentsByMessage.getOrDefault(row.id(), List.of()),
                        reactionsByMessage.getOrDefault(row.id(), List.of()),
                        row.replyToId() == null ? null : replyRefsById.get(row.replyToId()),
                        mentionsByMessage.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private static MessageResponse toResponse(MessageRow row,
                                              List<AttachmentResponse> attachments,
                                              List<ReactionView> reactions,
                                              ReplyRefResponse replyTo,
                                              List<MessageMentionResponse> mentions) {
        boolean deleted = row.deletedAt() != null;
        return new MessageResponse(
                row.id(), row.conversationId(), row.seq(), row.senderId(), row.senderDisplayName(),
                row.type().name(), deleted ? null : row.content(), row.clientMessageId(), row.replyToId(),
                replyTo, deleted ? List.of() : attachments, deleted ? List.of() : reactions,
                row.createdAt(), row.editedAt(), row.deletedAt(), deleted ? List.of() : mentions,
                !deleted && row.mentionAll());
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
                attachment.getDurationMs(),
                attachment.getBlurHash());
    }
}

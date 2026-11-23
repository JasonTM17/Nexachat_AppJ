package com.nexachat.scheduled;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.scheduled.dto.CreateScheduledMessageRequest;
import com.nexachat.scheduled.dto.ScheduledMessageResponse;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled-message CRUD.
 *
 * <p><b>Visibility:</b> scheduled messages are private to their author — the
 * list returns only the caller's rows, and every route fails closed with
 * {@code NOT_MEMBER} for non-members, the same family the message endpoints
 * use.</p>
 *
 * <p><b>Ceiling:</b> a sender holds at most {@value #MAX_PENDING_PER_SENDER}
 * PENDING rows per conversation. Creation takes {@code SELECT ... FOR
 * UPDATE} on the conversation row, so two racing creates cannot both slip
 * under the ceiling.</p>
 *
 * <p><b>Idempotent create:</b> {@code (sender_id, client_message_id)} is
 * unique. Replaying a key that stored a still-PENDING row returns HTTP 200
 * with that row; a key whose row already reached a terminal state answers
 * 409 {@code SCHEDULED_INVALID} echoing the status (Wukong amendment 7).</p>
 *
 * <p><b>sendAt clamp:</b> values outside {@code [now()+60s, now()+365d]} are
 * clamped, not rejected, and the response echoes the stored value (amendment
 * 10).</p>
 */
@Service
public class ScheduledMessageService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageService.class);

    static final int MAX_PENDING_PER_SENDER = 50;
    static final long MIN_AHEAD_SECONDS = 60L;
    static final long MAX_AHEAD_DAYS = 365L;
    static final int MAX_BODY_LENGTH = 10_000;
    static final int LIST_LIMIT = 100;
    /** A SENT row stays listed for this long so the client can fold it into history. */
    static final long RECENT_SENT_HOURS = 24L;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final ScheduledMessageRepository scheduledMessageRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final long claimLeaseSeconds;

    public ScheduledMessageService(ConversationRepository conversationRepository,
                                   ConversationMemberRepository memberRepository,
                                   ScheduledMessageRepository scheduledMessageRepository,
                                   UserRepository userRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   @Value("${nexachat.scheduled.claim-lease-seconds:60}")
                                   long claimLeaseSeconds) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.scheduledMessageRepository = scheduledMessageRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.claimLeaseSeconds = Math.max(1L, claimLeaseSeconds);
    }

    /**
     * The caller's scheduled rows for one conversation: every PENDING and
     * FAILED row (FAILED carries its {@code failureCode}) plus SENT rows
     * completed in the last {@value #RECENT_SENT_HOURS} hours, oldest first.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER}
     */
    @Transactional(readOnly = true)
    public List<ScheduledMessageResponse> list(UUID userId, UUID conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        requireMember(conversationId, userId);
        Instant sentCutoff = Instant.now().minus(RECENT_SENT_HOURS, ChronoUnit.HOURS);
        return scheduledMessageRepository.findListed(
                        conversationId, userId, sentCutoff, PageRequest.of(0, LIST_LIMIT))
                .stream()
                .map(ScheduledMessageService::toResponse)
                .toList();
    }

    /**
     * Creates a scheduled message. The {@code clientMessageId} is the
     * idempotency key and the eventual message's key, so offline retries are
     * safe end to end.
     *
     * @throws ApiException {@code NOT_MEMBER}, {@code E2EE_REQUIRED},
     *                      {@code VALIDATION_FAILED}, {@code SCHEDULED_LIMIT},
     *                      {@code SCHEDULED_INVALID}
     */
    @Transactional
    public ScheduledMessageCreateResult create(UUID userId, UUID conversationId,
                                               CreateScheduledMessageRequest request) {
        if (request == null || request.clientMessageId() == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "clientMessageId is required");
        }
        String body = normalizeBody(request.body());
        Instant sendAt = request.sendAt();
        if (sendAt == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "sendAt is required");
        }

        // Parent lock order is user -> conversation -> scheduled row; the FK
        // checks of the insert take those parent key-shares anyway, so taking
        // them explicitly first keeps creates serialized on the conversation.
        userRepository.lockForKeyShareSorted(List.of(userId));
        // A missing row is NOT_MEMBER, the same family as a conversation the
        // caller is not in (mirrors MessageService's member-write path).
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
        requireMember(conversationId, userId);
        if (conversation.isE2eeEnabled()) {
            throw ApiException.of(ApiErrorCode.E2EE_REQUIRED,
                    "Scheduled messages cannot be sent into end-to-end encrypted conversations");
        }
        Instant clampedSendAt = clampSendAt(sendAt);

        // A replay of the same key returns the stored outcome, even one that
        // would now fail validation — the first request is the one that ran.
        var existing = scheduledMessageRepository.findBySenderAndClientMessageId(
                userId, request.clientMessageId());
        if (existing.isPresent()) {
            return replayExisting(existing.get(), conversationId);
        }

        if (scheduledMessageRepository.countPending(conversationId, userId)
                >= MAX_PENDING_PER_SENDER) {
            throw ApiException.of(ApiErrorCode.SCHEDULED_LIMIT,
                    "At most " + MAX_PENDING_PER_SENDER + " pending scheduled messages"
                            + " per conversation",
                    Map.of("limit", MAX_PENDING_PER_SENDER));
        }

        UUID id = UUID.randomUUID();
        int inserted = scheduledMessageRepository.insertIgnoringDuplicate(
                id, conversationId, userId, request.clientMessageId(), body, clampedSendAt);
        if (inserted == 0) {
            // Lost the race against the same key on another request or
            // instance — the conversation lock only serializes this
            // conversation, so resolve the winner like a replay.
            ScheduledMessage winner = scheduledMessageRepository
                    .findBySenderAndClientMessageId(userId, request.clientMessageId())
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.INTERNAL_ERROR,
                            "Scheduled message insert/read-back inconsistency"));
            return replayExisting(winner, conversationId);
        }

        ScheduledMessage stored = scheduledMessageRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.INTERNAL_ERROR,
                        "Scheduled message insert/read-back inconsistency"));
        log.info("Scheduled message {} created in conversation {} for {}",
                id, conversationId, stored.getSendAt());
        publishChanged(stored);
        return new ScheduledMessageCreateResult(toResponse(stored), true);
    }

    /**
     * Cancels one of the caller's PENDING rows. The cancel is guarded by the
     * claim lease (amendment 2): a row the dispatcher claimed within the last
     * {@code claim-lease-seconds} answers 409 {@code DISPATCHING} rather than
     * racing the send, and so does a row already in a terminal state.
     *
     * @throws ApiException {@code NOT_MEMBER}, {@code NOT_FOUND},
     *                      {@code DISPATCHING}
     */
    @Transactional
    public void cancel(UUID userId, UUID conversationId, UUID scheduledId) {
        requireMember(conversationId, userId);
        int updated = scheduledMessageRepository.cancelIfCancelable(
                scheduledId, userId, conversationId, claimLeaseSeconds);
        // The update missed — distinguish "not the caller's row" (404) from
        // "exists but dispatch is in flight or already terminal" (409).
        ScheduledMessage row = scheduledMessageRepository.findById(scheduledId)
                .filter(m -> conversationId.equals(m.getConversation().getId())
                        && userId.equals(m.getSender().getId()))
                .orElse(null);
        if (updated == 1) {
            log.info("Scheduled message {} canceled in conversation {}", scheduledId, conversationId);
            if (row != null) {
                publishChanged(row);
            } else {
                // The row already left the visible set in a race — still tell
                // the scheduler to recompute and the author's devices to sync.
                eventPublisher.publishEvent(new ScheduledMessageChangedEvent(
                        scheduledId, conversationId, userId, null,
                        ScheduledMessageStatus.CANCELED.name(), null, null, null));
            }
            return;
        }
        if (row == null) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "Scheduled message not found");
        }
        throw ApiException.of(ApiErrorCode.DISPATCHING,
                "Scheduled message is already " + row.getStatus().name().toLowerCase()
                        + " and cannot be canceled",
                Map.of("status", row.getStatus().name()));
    }

    // ------------------------------------------------------------- internals

    private ScheduledMessageCreateResult replayExisting(ScheduledMessage row, UUID conversationId) {
        if (!conversationId.equals(row.getConversation().getId())) {
            // The idempotency key already stored a scheduled message in
            // ANOTHER conversation — a client bug, not a retry (mirrors the
            // send path's clientMessageId contract).
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "clientMessageId already used in a different conversation");
        }
        if (row.getStatus() == ScheduledMessageStatus.PENDING) {
            return new ScheduledMessageCreateResult(toResponse(row), false);
        }
        throw ApiException.of(ApiErrorCode.SCHEDULED_INVALID,
                "clientMessageId already used by a " + row.getStatus().name()
                        + " scheduled message",
                Map.of("status", row.getStatus().name()));
    }

    /** Amendment 10: never reject — pin to [now+60s, now+365d] and echo the stored value. */
    private static Instant clampSendAt(Instant sendAt) {
        Instant now = Instant.now();
        Instant floor = now.plus(MIN_AHEAD_SECONDS, ChronoUnit.SECONDS);
        Instant ceiling = now.plus(MAX_AHEAD_DAYS, ChronoUnit.DAYS);
        if (sendAt.isBefore(floor)) {
            return floor;
        }
        if (sendAt.isAfter(ceiling)) {
            return ceiling;
        }
        return sendAt;
    }

    /** Same content contract as a TEXT send: non-blank, <=10000 chars, no NUL. */
    private static String normalizeBody(String body) {
        if (body == null || body.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "body is required");
        }
        if (body.length() > MAX_BODY_LENGTH || body.indexOf('\0') >= 0) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "body must be at most " + MAX_BODY_LENGTH + " characters");
        }
        return body;
    }

    private void requireMember(UUID conversationId, UUID userId) {
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
    }

    private void publishChanged(ScheduledMessage row) {
        eventPublisher.publishEvent(new ScheduledMessageChangedEvent(
                row.getId(), row.getConversation().getId(), row.getSender().getId(),
                row.getClientMessageId(), row.getStatus().name(), row.getFailureCode(),
                row.getSendAt(), row.getSentMessageId()));
    }

    private static ScheduledMessageResponse toResponse(ScheduledMessage row) {
        return new ScheduledMessageResponse(
                row.getId(),
                row.getConversation().getId(),
                row.getClientMessageId(),
                row.getBody(),
                row.getSendAt(),
                row.getStatus().name(),
                row.getFailureCode(),
                row.getSentMessageId(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}

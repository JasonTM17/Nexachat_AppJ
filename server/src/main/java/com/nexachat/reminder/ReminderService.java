package com.nexachat.reminder;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.RequestIdempotency;
import com.nexachat.message.RequestIdempotencyRepository;
import com.nexachat.reminder.dto.CreateReminderRequest;
import com.nexachat.reminder.dto.ReminderResponse;
import com.nexachat.reminder.dto.UpdateReminderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversation reminder use cases.
 *
 * <p><b>Visibility:</b> every member of the conversation can list reminders.
 * A reminder is created by a member and is editable/deletable by its creator
 * or, in a GROUP, by an admin. Non-members fail closed with
 * {@code NOT_MEMBER}.</p>
 *
 * <p><b>Ceiling:</b> a conversation holds at most
 * {@value #MAX_ACTIVE_REMINDERS} active reminders. Creation takes
 * {@code SELECT ... FOR UPDATE} on the conversation row, so two racing creates
 * cannot both slip under the ceiling.</p>
 *
 * <p><b>Idempotent create:</b> an optional {@code Idempotency-Key} reuses the
 * shared {@code request_idempotency} cache. Because the generic
 * {@code IdempotencyService} only stores a void outcome, this service stores
 * the created reminder id in the cache row and replays the entity. A replay
 * returns HTTP 200 with the original reminder; a fresh create returns 201.</p>
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    static final int MAX_ACTIVE_REMINDERS = 50;
    static final int MAX_TITLE_LENGTH = 200;
    static final long MAX_AHEAD_DAYS = 365L;
    static final int MAX_KEY_LENGTH = 64;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationReminderRepository reminderRepository;
    private final RequestIdempotencyRepository idempotencyRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReminderService(ConversationRepository conversationRepository,
                           ConversationMemberRepository memberRepository,
                           ConversationReminderRepository reminderRepository,
                           RequestIdempotencyRepository idempotencyRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.reminderRepository = reminderRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Upcoming reminders for one conversation, oldest first.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER}
     */
    @Transactional(readOnly = true)
    public List<ReminderResponse> list(UUID userId, UUID conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        ConversationMember member = requireMember(conversationId, userId);
        Instant now = Instant.now();
        return reminderRepository.findUpcoming(
                        conversationId, now,
                        org.springframework.data.domain.PageRequest.of(0, MAX_ACTIVE_REMINDERS))
                .stream()
                .map(reminder -> toResponse(reminder, conversation.getType(), member, userId))
                .toList();
    }

    /**
     * Creates a reminder on the shared board. The optional
     * {@code Idempotency-Key} makes an offline outbox retry safe.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER},
     *                      {@code VALIDATION_FAILED}, {@code NOT_FOUND}
     *                      (replayed key whose reminder no longer exists),
     *                      {@code IDEMPOTENCY_KEY_REUSED}
     */
    @Transactional
    public ReminderCreateResult create(UUID userId, UUID conversationId,
                                       CreateReminderRequest request, String idempotencyKey) {
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        ConversationMember member = requireMember(conversationId, userId);

        String title = normalizeTitle(request == null ? null : request.title());
        ReminderRepeat repeat = parseRepeatOrDefault(request == null ? null : request.repeat());
        Instant remindAt = request == null ? null : request.remindAt();
        if (remindAt == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "remindAt is required");
        }

        String key = normalizeIdempotencyKey(idempotencyKey);
        String endpoint = createEndpoint(conversationId);
        String payloadHash = hash(canonicalCreatePayload(title, remindAt, repeat));

        // Check the replay cache before the time window and ceiling: a retry of
        // a successfully created reminder must return the original result even
        // if its remindAt has since passed.
        if (key != null) {
            Optional<RequestIdempotency> previous =
                    idempotencyRepository.findByUserIdAndKey(userId, key);
            if (previous.isPresent()) {
                requireSameOperation(previous.get(), endpoint, payloadHash);
                return replayResult(previous.get(), conversation, member, userId);
            }
        }

        validateRemindAt(remindAt);
        if (reminderRepository.countActive(conversationId) >= MAX_ACTIVE_REMINDERS) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "At most " + MAX_ACTIVE_REMINDERS + " active reminders per conversation",
                    Map.of("limit", MAX_ACTIVE_REMINDERS));
        }

        ConversationReminder reminder = ConversationReminder.create(
                conversation, member.getUser(), title, remindAt, repeat);
        reminderRepository.save(reminder);

        if (key != null) {
            int inserted = idempotencyRepository.insert(
                    userId, key, endpoint, payloadHash, reminder.getId().toString());
            if (inserted == 0) {
                RequestIdempotency winner = idempotencyRepository.findByUserIdAndKey(userId, key)
                        .orElseThrow(() -> ApiException.of(
                                ApiErrorCode.INTERNAL_ERROR, "Idempotency row disappeared"));
                requireSameOperation(winner, endpoint, payloadHash);
                // The conversation lock serializes same-conversation creates;
                // a conflict here means another instance won. Drop this
                // duplicate and return the winner's reminder.
                ConversationReminder winnerReminder = requireReplayReminder(
                        winner, conversation.getId());
                reminderRepository.delete(reminder);
                return new ReminderCreateResult(
                        toResponse(winnerReminder, conversation.getType(), member, userId), false);
            }
        }

        log.info("Reminder {} created in conversation {}", reminder.getId(), conversationId);
        ReminderCreateResult result = new ReminderCreateResult(
                toResponse(reminder, conversation.getType(), member, userId), true);
        eventPublisher.publishEvent(new ReminderScheduleChangedEvent(reminder.getId()));
        return result;
    }

    /**
     * Partial edit; at least one field must be present. Changing the schedule
     * resets the repeat anchor to the effective next occurrence.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER},
     *                      {@code NOT_ADMIN}, {@code FORBIDDEN},
     *                      {@code NOT_FOUND}, {@code VALIDATION_FAILED}
     */
    @Transactional
    public ReminderResponse update(UUID userId, UUID conversationId, UUID reminderId,
                                   UpdateReminderRequest request) {
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        ConversationMember member = requireMember(conversationId, userId);
        ConversationReminder reminder = requireActiveReminder(conversationId, reminderId);
        requireEditRole(conversation, member, reminder, userId);

        boolean titleProvided = request != null && request.title() != null;
        boolean remindAtProvided = request != null && request.remindAt() != null;
        boolean repeatProvided = request != null && request.repeat() != null;
        if (!titleProvided && !remindAtProvided && !repeatProvided) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "At least one of title, remindAt or repeat is required");
        }

        String title = titleProvided ? normalizeTitle(request.title()) : null;
        ReminderRepeat repeat = repeatProvided ? parseExplicitRepeat(request.repeat()) : null;
        boolean repeatChanged = repeat != null && repeat != reminder.getRepeatRule();
        Instant remindAt = null;
        if (remindAtProvided) {
            validateRemindAt(request.remindAt());
            remindAt = request.remindAt();
        }

        reminder.edit(title, remindAt, repeat, remindAtProvided || repeatChanged, Instant.now());
        log.info("Reminder {} updated in conversation {}", reminderId, conversationId);
        ReminderResponse result = toResponse(reminder, conversation.getType(), member, userId);
        eventPublisher.publishEvent(new ReminderScheduleChangedEvent(reminder.getId()));
        return result;
    }

    /**
     * Hard-deletes an active reminder. A fired one-off reminder is no longer
     * on the board and is reported as {@code NOT_FOUND}.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER},
     *                      {@code NOT_ADMIN}, {@code FORBIDDEN}, {@code NOT_FOUND}
     */
    @Transactional
    public void delete(UUID userId, UUID conversationId, UUID reminderId) {
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        ConversationMember member = requireMember(conversationId, userId);
        ConversationReminder reminder = requireActiveReminder(conversationId, reminderId);
        requireEditRole(conversation, member, reminder, userId);
        reminderRepository.delete(reminder);
        eventPublisher.publishEvent(new ReminderScheduleChangedEvent(reminderId));
        log.info("Reminder {} deleted in conversation {}", reminderId, conversationId);
    }

    // ------------------------------------------------------------- internals

    private ReminderCreateResult replayResult(RequestIdempotency row, Conversation conversation,
                                              ConversationMember member, UUID userId) {
        ConversationReminder reminder = requireReplayReminder(row, conversation.getId());
        return new ReminderCreateResult(
                toResponse(reminder, conversation.getType(), member, userId), false);
    }

    private ConversationReminder requireReplayReminder(RequestIdempotency row, UUID conversationId) {
        UUID reminderId;
        try {
            reminderId = UUID.fromString(row.getResponseHash());
        } catch (IllegalArgumentException ex) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Idempotency row is corrupted");
        }
        return reminderRepository.findById(reminderId)
                .filter(reminder -> reminder.getConversation().getId().equals(conversationId))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Reminder not found"));
    }

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
    }

    private ConversationReminder requireActiveReminder(UUID conversationId, UUID reminderId) {
        return reminderRepository.findByIdForUpdate(reminderId)
                .filter(reminder -> reminder.getConversation().getId().equals(conversationId))
                .filter(reminder -> reminder.getFiredAt() == null)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Reminder not found"));
    }

    /** Creator, or a GROUP admin; a DIRECT non-creator has no admin path. */
    private static void requireEditRole(Conversation conversation, ConversationMember member,
                                        ConversationReminder reminder, UUID userId) {
        if (reminder.getCreatedBy().getId().equals(userId)) {
            return;
        }
        if (conversation.getType() == Conversation.Type.GROUP) {
            if (member.getRole() != ConversationMember.Role.ADMIN) {
                throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Group admin role required");
            }
            return;
        }
        throw ApiException.of(ApiErrorCode.FORBIDDEN, "Only the reminder creator can change it");
    }

    private static ReminderResponse toResponse(ConversationReminder reminder,
                                               Conversation.Type conversationType,
                                               ConversationMember member, UUID userId) {
        boolean canEdit = reminder.getCreatedBy().getId().equals(userId)
                || (conversationType == Conversation.Type.GROUP
                    && member.getRole() == ConversationMember.Role.ADMIN);
        return new ReminderResponse(
                reminder.getId(),
                reminder.getConversation().getId(),
                reminder.getTitle(),
                reminder.getRemindAt(),
                reminder.getRepeatRule(),
                reminder.getCreatedBy().getId(),
                reminder.getCreatedBy().getDisplayName(),
                canEdit,
                reminder.getCreatedAt(),
                reminder.getUpdatedAt());
    }

    private static String normalizeTitle(String raw) {
        if (raw == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "title is required");
        }
        String title = raw.strip();
        if (title.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "title must not be blank");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "title must not exceed " + MAX_TITLE_LENGTH + " characters");
        }
        return title;
    }

    private static ReminderRepeat parseRepeatOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReminderRepeat.NONE;
        }
        return parseRepeat(raw);
    }

    private static ReminderRepeat parseExplicitRepeat(String raw) {
        if (raw.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "repeat must be NONE, DAILY, WEEKLY or MONTHLY");
        }
        return parseRepeat(raw);
    }

    private static ReminderRepeat parseRepeat(String raw) {
        try {
            return ReminderRepeat.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "repeat must be NONE, DAILY, WEEKLY or MONTHLY");
        }
    }

    private static void validateRemindAt(Instant remindAt) {
        Instant now = Instant.now();
        if (!remindAt.isAfter(now)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "remindAt must be in the future");
        }
        if (remindAt.isAfter(now.plus(MAX_AHEAD_DAYS, ChronoUnit.DAYS))) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "remindAt must be at most " + MAX_AHEAD_DAYS + " days ahead");
        }
    }

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.strip();
        if (key.length() > MAX_KEY_LENGTH) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must not exceed " + MAX_KEY_LENGTH + " characters");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException ex) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be a UUID");
        }
        return key;
    }

    private static void requireSameOperation(RequestIdempotency row, String endpoint,
                                             String payloadHash) {
        if (!row.getEndpoint().equals(endpoint) || !row.getPayloadHash().equals(payloadHash)) {
            throw ApiException.of(ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different endpoint or payload");
        }
    }

    private static String createEndpoint(UUID conversationId) {
        return "POST /api/v1/conversations/" + conversationId + "/reminders";
    }

    private static String canonicalCreatePayload(String title, Instant remindAt,
                                                 ReminderRepeat repeat) {
        return title + "\n" + remindAt + "\n" + repeat.name();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available", ex);
        }
    }
}

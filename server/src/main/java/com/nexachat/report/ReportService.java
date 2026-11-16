package com.nexachat.report;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.Message;
import com.nexachat.message.MessageRepository;
import com.nexachat.report.dto.CreateReportRequest;
import com.nexachat.report.dto.ReportPageResponse;
import com.nexachat.report.dto.ReportResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Abuse reports (V38 safety): {@code POST /reports} and the paged
 * {@code GET /reports/mine} list.
 *
 * <p><b>Validation order:</b> rate limit → target authorization → open-report
 * dedupe → insert. A reporter must be able to see what they report: a USER
 * target must exist and be active, a MESSAGE target must sit in a
 * conversation the reporter belonged to when it was created (and a
 * peer-suppressed row reports as MESSAGE_NOT_FOUND to everyone but its
 * sender — the same "cannot see it" boundary as every other read model),
 * and a CONVERSATION target requires current membership.
 *
 * <p><b>Idempotency:</b> at most one OPEN report per (reporter, target) —
 * the partial unique index enforces it; a repeat submit returns the
 * existing row with {@code created=false} so client retries converge.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    static final int SUBMIT_LIMIT = 20;
    static final Duration SUBMIT_WINDOW = Duration.ofHours(1);
    static final int MAX_PAGE_LIMIT = 100;
    static final int DEFAULT_PAGE_LIMIT = 50;
    static final int SNAPSHOT_MAX = 500;

    private record Cursor(Instant createdAt, UUID id) {
    }

    /** Outcome of {@link #submit}: the report plus whether this call created it. */
    public record Result(ReportResponse report, boolean created) {
    }

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final RateLimitService rateLimitService;
    private final Clock clock;

    public ReportService(ReportRepository reportRepository,
                         UserRepository userRepository,
                         MessageRepository messageRepository,
                         ConversationRepository conversationRepository,
                         ConversationMemberRepository memberRepository,
                         RateLimitService rateLimitService,
                         Clock clock) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.rateLimitService = rateLimitService;
        this.clock = clock;
    }

    /**
     * Files a report or returns the existing OPEN one. Deliberately not one
     * transaction: the insert's own repository transaction must roll back
     * before the dedupe re-read on a concurrent duplicate.
     */
    public Result submit(UUID userId, CreateReportRequest request) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                "report:submit:" + userId, SUBMIT_LIMIT, SUBMIT_WINDOW);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Too many reports",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }

        Message message = switch (request.targetType()) {
            case USER -> {
                if (userId.equals(request.targetId())) {
                    throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Cannot report yourself");
                }
                User target = userRepository.findById(request.targetId())
                        .filter(u -> u.getDeletedAt() == null)
                        .orElseThrow(() -> ApiException.of(
                                ApiErrorCode.USER_NOT_FOUND, "User not found"));
                yield null;
            }
            case MESSAGE -> validateMessageTarget(userId, request.targetId());
            case CONVERSATION -> {
                if (!conversationRepository.existsById(request.targetId())) {
                    throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND,
                            "Conversation not found");
                }
                if (!memberRepository.existsByConversation_IdAndUser_Id(
                        request.targetId(), userId)) {
                    throw ApiException.of(ApiErrorCode.NOT_MEMBER,
                            "Not a member of this conversation");
                }
                yield null;
            }
        };

        Optional<Report> open = reportRepository.findOpen(
                userId, request.targetType(), request.targetId());
        if (open.isPresent()) {
            return new Result(ReportResponse.from(open.get()), false);
        }

        String snapshot = null;
        boolean reporterProvided = false;
        if (message != null) {
            if (message.getEncryption() == null) {
                // Server-readable: copy the evidence itself — the reporter's
                // own snapshot field is ignored so it cannot be forged.
                snapshot = truncate(message.getContent());
            } else {
                snapshot = truncate(request.messageSnapshot());
                reporterProvided = snapshot != null;
            }
        }
        String details = normalizeOptional(request.details());

        try {
            Report report = reportRepository.saveAndFlush(Report.create(
                    userRepository.getReferenceById(userId), request.targetType(),
                    request.targetId(), request.reason(), details, snapshot,
                    reporterProvided, clock.instant()));
            log.info("Report {} filed by {} on {} {}", report.getId(), userId,
                    request.targetType(), request.targetId());
            return new Result(ReportResponse.from(report), true);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // Lost the race with a same-target submit: the partial unique
            // index refused the row — return the winner, same as a retry.
            return reportRepository.findOpen(userId, request.targetType(), request.targetId())
                    .map(report -> new Result(ReportResponse.from(report), false))
                    .orElseThrow(() -> concurrentDuplicate);
        }
    }

    /** The reporter must have been able to read the message when it arrived. */
    private Message validateMessageTarget(UUID userId, UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        if (message.isPeerSuppressed() && !message.getSender().getId().equals(userId)) {
            throw ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found");
        }
        if (!memberRepository.existsAdmittedAt(
                message.getConversation().getId(), userId, message.getCreatedAt())) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
        return message;
    }

    /** Newest-first keyset page of the reporter's own filings. */
    @Transactional(readOnly = true)
    public ReportPageResponse listMine(UUID userId, Integer limit, String cursorToken) {
        int pageSize = normalizeLimit(limit);
        Cursor cursor = cursorToken == null ? null : decodeCursor(cursorToken);
        List<Report> rows = cursor == null
                ? reportRepository.findMinePageFirst(userId, PageRequest.of(0, pageSize + 1))
                : reportRepository.findMinePageAfter(userId, cursor.createdAt(), cursor.id(),
                        PageRequest.of(0, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<Report> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1))
                : null;
        return new ReportPageResponse(
                page.stream().map(ReportResponse::from).toList(), nextCursor);
    }

    private static String truncate(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= SNAPSHOT_MAX ? content : content.substring(0, SNAPSHOT_MAX);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        return limit < 1 ? 1 : Math.min(limit, MAX_PAGE_LIMIT);
    }

    private static String encodeCursor(Report report) {
        String value = "v1|" + report.getCreatedAt() + "|" + report.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String token) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] fields = value.split("\\|", -1);
            if (fields.length != 3 || !"v1".equals(fields[0])) {
                throw new IllegalArgumentException("invalid cursor version");
            }
            return new Cursor(Instant.parse(fields[1]), UUID.fromString(fields[2]));
        } catch (RuntimeException ex) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid reports cursor");
        }
    }
}

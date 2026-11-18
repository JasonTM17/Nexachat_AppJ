package com.nexachat.report;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.Message;
import com.nexachat.message.MessageRepository;
import com.nexachat.report.dto.CreateReportRequest;
import com.nexachat.report.dto.ReportPageResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReportService} unit tests — submit ordering (rate limit → target
 * authorization → dedupe → insert), per-target authorization, the evidence
 * snapshot contract, and the {@code /mine} page.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private RateLimitService rateLimitService;

    private ReportService reportService;

    private final UUID reporterId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reportService = new ReportService(
                reportRepository, userRepository, messageRepository, conversationRepository,
                memberRepository, rateLimitService,
                Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC));
        lenient().when(rateLimitService.tryConsume(anyString(), eq(20L), any()))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        lenient().when(reportRepository.findOpen(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(reportRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(userRepository.getReferenceById(reporterId)).thenReturn(mock(User.class));
    }

    private CreateReportRequest request(Report.TargetType type, UUID id) {
        return new CreateReportRequest(type, id, Report.Reason.SPAM, "details", null);
    }

    private Message message(UUID senderId, UUID conversationId, String encryption, String content) {
        Message message = mock(Message.class);
        User sender = mock(User.class);
        Conversation conversation = mock(Conversation.class);
        lenient().when(sender.getId()).thenReturn(senderId);
        lenient().when(conversation.getId()).thenReturn(conversationId);
        lenient().when(message.getSender()).thenReturn(sender);
        lenient().when(message.getConversation()).thenReturn(conversation);
        lenient().when(message.getCreatedAt()).thenReturn(Instant.parse("2026-11-15T10:00:00Z"));
        lenient().when(message.isPeerSuppressed()).thenReturn(false);
        lenient().when(message.getEncryption()).thenReturn(encryption);
        lenient().when(message.getContent()).thenReturn(content);
        return message;
    }

    // -------------------------------------------------------------- rate limit

    @Test
    void submit_rateLimited_throwsBeforeAnyValidation() {
        when(rateLimitService.tryConsume(anyString(), eq(20L), any()))
                .thenReturn(new RateLimitService.Consumption(false, 42));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(reporterId, request(Report.TargetType.USER, targetId)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
        assertThat(ex.getDetails()).containsEntry("retryAfterSeconds", 42L);
        verify(userRepository, never()).findById(any());
    }

    // ------------------------------------------------------------- USER target

    @Test
    void submit_self_isValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(reporterId, request(Report.TargetType.USER, reporterId)));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void submit_userTarget_unknownOrDeleted_isUserNotFound() {
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(reporterId, request(Report.TargetType.USER, targetId)));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);

        User deleted = mock(User.class);
        when(deleted.getDeletedAt()).thenReturn(Instant.now());
        when(userRepository.findById(targetId)).thenReturn(Optional.of(deleted));
        ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(reporterId, request(Report.TargetType.USER, targetId)));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }

    // ---------------------------------------------------------- MESSAGE target

    @Test
    void submit_messageTarget_missing_isMessageNotFound() {
        when(messageRepository.findById(targetId)).thenReturn(Optional.empty());
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(reporterId, request(Report.TargetType.MESSAGE, targetId)));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void submit_messageTarget_notAdmitted_isNotMember() {
        UUID senderId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = message(senderId, conversationId, null, "spam text");
        when(messageRepository.findById(targetId)).thenReturn(Optional.of(message));
        when(memberRepository.existsAdmittedAt(eq(conversationId), eq(reporterId), any()))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(reporterId, request(Report.TargetType.MESSAGE, targetId)));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void submit_messageTarget_suppressed_isMessageNotFoundForNonSender() {
        Message suppressed = message(UUID.randomUUID(), UUID.randomUUID(), null, "x");
        when(suppressed.isPeerSuppressed()).thenReturn(true);
        when(messageRepository.findById(targetId)).thenReturn(Optional.of(suppressed));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(reporterId, request(Report.TargetType.MESSAGE, targetId)));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void submit_messageTarget_plaintext_snapshotsServerSide() {
        UUID senderId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String longContent = "x".repeat(600);
        Message message = message(senderId, conversationId, null, longContent);
        when(messageRepository.findById(targetId)).thenReturn(Optional.of(message));
        when(memberRepository.existsAdmittedAt(eq(conversationId), eq(reporterId), any()))
                .thenReturn(true);

        ReportService.Result result = reportService.submit(
                reporterId, new CreateReportRequest(
                        Report.TargetType.MESSAGE, targetId, Report.Reason.HARASSMENT,
                        "details", "FORGED"));

        assertThat(result.created()).isTrue();
        // The server copied the content itself — a reporter-supplied snapshot
        // on a readable message is ignored, and the copy is capped at 500.
        assertThat(result.report().messageSnapshot())
                .isEqualTo("x".repeat(500));
        org.mockito.ArgumentCaptor<Report> captor = org.mockito.ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isSnapshotReporterProvided()).isFalse();
    }

    @Test
    void submit_messageTarget_plaintext_snapshotNeverEndsInLoneSurrogate() {
        UUID senderId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        // 499 chars + one surrogate-pair emoji: char index 499 is the high
        // surrogate, so a naive substring(0, 500) would store a lone half.
        String content = "x".repeat(499) + "\uD83C\uDF89" + "tail";
        Message message = message(senderId, conversationId, null, content);
        when(messageRepository.findById(targetId)).thenReturn(Optional.of(message));
        when(memberRepository.existsAdmittedAt(eq(conversationId), eq(reporterId), any()))
                .thenReturn(true);

        ReportService.Result result = reportService.submit(
                reporterId, new CreateReportRequest(
                        Report.TargetType.MESSAGE, targetId, Report.Reason.HARASSMENT,
                        "details", null));

        String snapshot = result.report().messageSnapshot();
        assertThat(snapshot).isEqualTo("x".repeat(499));
        assertThat(snapshot.codePoints().count()).isEqualTo(499);
    }

    @Test
    void submit_messageTarget_encrypted_usesReporterSnapshot() {
        UUID senderId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = message(senderId, conversationId, "E2EE_V1", "ciphertext");
        when(messageRepository.findById(targetId)).thenReturn(Optional.of(message));
        when(memberRepository.existsAdmittedAt(eq(conversationId), eq(reporterId), any()))
                .thenReturn(true);

        ReportService.Result result = reportService.submit(
                reporterId, new CreateReportRequest(
                        Report.TargetType.MESSAGE, targetId, Report.Reason.SCAM,
                        null, "the plaintext I received"));

        assertThat(result.created()).isTrue();
        assertThat(result.report().messageSnapshot()).isEqualTo("the plaintext I received");
        org.mockito.ArgumentCaptor<Report> captor = org.mockito.ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isSnapshotReporterProvided()).isTrue();
    }

    // ------------------------------------------------------- CONVERSATION target

    @Test
    void submit_conversationTarget_missing_isConversationNotFound() {
        when(conversationRepository.existsById(targetId)).thenReturn(false);
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(
                        reporterId, request(Report.TargetType.CONVERSATION, targetId)));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void submit_conversationTarget_notMember_isNotMember() {
        when(conversationRepository.existsById(targetId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(targetId, reporterId))
                .thenReturn(false);
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> reportService.submit(
                        reporterId, request(Report.TargetType.CONVERSATION, targetId)));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    // -------------------------------------------------------------- idempotency

    @Test
    void submit_existingOpenReport_returnsItWithoutInserting() {
        Report existing = Report.create(mock(User.class), Report.TargetType.USER, targetId,
                Report.Reason.SPAM, "first", null, false, Instant.now());
        when(userRepository.findById(targetId)).thenReturn(Optional.of(mock(User.class)));
        when(reportRepository.findOpen(reporterId, Report.TargetType.USER, targetId))
                .thenReturn(Optional.of(existing));

        ReportService.Result result = reportService.submit(
                reporterId, request(Report.TargetType.USER, targetId));

        assertThat(result.created()).isFalse();
        assertThat(result.report().id()).isEqualTo(existing.getId());
        verify(reportRepository, never()).saveAndFlush(any());
    }

    // ----------------------------------------------------------------- /mine

    @Test
    void listMine_pagesNewestFirst() {
        Report r1 = Report.create(mock(User.class), Report.TargetType.USER, UUID.randomUUID(),
                Report.Reason.SPAM, null, null, false, Instant.parse("2026-11-15T11:00:00Z"));
        Report r2 = Report.create(mock(User.class), Report.TargetType.MESSAGE, UUID.randomUUID(),
                Report.Reason.OTHER, "d", null, false, Instant.parse("2026-11-15T10:00:00Z"));
        Report r3 = Report.create(mock(User.class), Report.TargetType.USER, UUID.randomUUID(),
                Report.Reason.SCAM, null, null, false, Instant.parse("2026-11-15T09:00:00Z"));
        when(reportRepository.findMinePageFirst(eq(reporterId), any(Pageable.class)))
                .thenReturn(List.of(r1, r2, r3));

        ReportPageResponse page = reportService.listMine(reporterId, 2, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).id()).isEqualTo(r1.getId());
        assertThat(page.nextCursor()).isNotNull();
    }
}

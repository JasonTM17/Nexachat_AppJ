package com.nexachat.archive;

import com.nexachat.archive.dto.ArchiveAttachmentRow;
import com.nexachat.archive.dto.ArchiveItem;
import com.nexachat.archive.dto.ArchiveLinkRow;
import com.nexachat.archive.dto.ArchiveResponse;
import com.nexachat.auth.JwtProperties;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link ArchiveService}: parameter validation, membership
 * ordering (cursor is decoded only after authZ), page assembly and the
 * single-query-per-page discipline.
 */
@ExtendWith(MockitoExtension.class)
class ArchiveServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private ArchiveRepository archiveRepository;

    private ArchiveService service;
    private final ArchiveCursorCodec codec = new ArchiveCursorCodec(
            new JwtProperties("test-only-secret-0123456789abcdef-0123456789", 900, 14));

    private final UUID callerId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ArchiveService(
                conversationRepository, memberRepository, archiveRepository, codec);
    }

    // ------------------------------------------------------------ validation

    @Test
    void rejectsMissingUnknownTypesAndInvalidLimits() {
        assertValidation(() -> service.browse(callerId, conversationId, null, null, null, null, null, null));
        assertValidation(() -> service.browse(callerId, conversationId, "", null, null, null, null, null));
        assertValidation(() -> service.browse(callerId, conversationId, "VIDEO", null, null, null, null, null));
        assertValidation(() -> service.browse(callerId, conversationId, "MEDIA", null, null, null, 0, null));
        assertValidation(() -> service.browse(callerId, conversationId, "MEDIA", null, null, null, 51, null));
    }

    @Test
    void rejectsMalformedAndInvertedDateRanges() {
        assertValidation(() -> service.browse(
                callerId, conversationId, "LINK", null, "yesterday", null, null, null));
        assertValidation(() -> service.browse(
                callerId, conversationId, "LINK", null, null, "not-a-date", null, null));
        assertValidation(() -> service.browse(callerId, conversationId, "LINK", null,
                "2026-11-10T00:00:00Z", "2026-11-09T00:00:00Z", null, null));
    }

    @Test
    void unknownConversationIs404AndNonMemberIs403() {
        ApiException missing = catchThrowableOfType(
                () -> service.browse(callerId, conversationId, "MEDIA", null, null, null, null, null),
                ApiException.class);
        assertThat(missing.getCode()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);

        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        ApiException forbidden = catchThrowableOfType(
                () -> service.browse(callerId, conversationId, "MEDIA", null, null, null, null, null),
                ApiException.class);
        assertThat(forbidden.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void membershipIsCheckedBeforeTheCursorIsDecoded() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, callerId))
                .thenReturn(false);

        ApiException error = catchThrowableOfType(
                () -> service.browse(callerId, conversationId, "MEDIA", null, null, null, null, "garbage"),
                ApiException.class);

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void tamperedCursorIs400ForAMember() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, callerId))
                .thenReturn(true);

        ApiException error = catchThrowableOfType(
                () -> service.browse(callerId, conversationId, "MEDIA", null, null, null, null, "garbage"),
                ApiException.class);

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    // ------------------------------------------------------- attachment pages

    @Test
    void mediaPageUsesOneQueryAndEmitsCursorFromTheLastVisibleItem() {
        stubMember();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<ArchiveAttachmentRow> rows = List.of(
                attachmentRow(30, "image/png", first),
                attachmentRow(29, "video/mp4", second),
                attachmentRow(28, "image/jpeg", UUID.randomUUID()));
        when(archiveRepository.findMediaPage(eq(callerId), eq(conversationId), isNull(), isNull(), isNull(),
                isNull(), isNull(), any(Pageable.class))).thenReturn(rows);

        ArchiveResponse response = service.browse(
                callerId, conversationId, "MEDIA", null, null, null, 2, null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).kind()).isEqualTo(ArchiveItemKind.IMAGE);
        assertThat(response.items().get(1).kind()).isEqualTo(ArchiveItemKind.VIDEO);
        assertThat(response.items().get(0).attachment()).isNotNull();
        assertThat(response.items().get(0).link()).isNull();
        assertThat(response.nextCursor()).isNotBlank();

        ArchiveCursor decoded = codec.decode(response.nextCursor(), conversationId, ArchiveKind.MEDIA);
        assertThat(decoded.seq()).isEqualTo(29);
        assertThat(decoded.sub()).isEqualTo(second.toString());
        verify(archiveRepository, times(1)).findMediaPage(eq(callerId), eq(conversationId), isNull(), isNull(),
                isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void filePageMapsNonMediaAttachmentsToFileItems() {
        stubMember();
        UUID attachmentId = UUID.randomUUID();
        when(archiveRepository.findFilePage(eq(callerId), eq(conversationId), isNull(), isNull(), isNull(),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(attachmentRow(9, "application/pdf", attachmentId)));

        ArchiveResponse response = service.browse(
                callerId, conversationId, "FILE", null, null, null, 30, null);

        assertThat(response.items()).hasSize(1);
        ArchiveItem item = response.items().get(0);
        assertThat(item.kind()).isEqualTo(ArchiveItemKind.FILE);
        assertThat(item.attachment().fileName()).isEqualTo("file.bin");
        assertThat(item.attachment().durationMs()).isEqualTo(1234);
        assertThat(item.attachment().blurHash()).isEqualTo("LEHV6nWB2yk8");
        assertThat(item.attachment().width()).isEqualTo(10);
        assertThat(item.attachment().height()).isEqualTo(20);
        assertThat(response.nextCursor()).isNull();
    }

    // -------------------------------------------------------------- link pages

    @Test
    void linkPageCutsInsideAMessageAndResumesAtTheNextLink() {
        stubMember();
        long seq = 50;
        when(archiveRepository.findLinkMessagePage(eq(callerId), eq(conversationId), isNull(), isNull(), isNull(),
                isNull(), any(Pageable.class))).thenReturn(List.of(
                linkRow(seq, "one https://a.example/1 two https://a.example/2 three https://a.example/3")));

        ArchiveResponse response = service.browse(
                callerId, conversationId, "LINK", null, null, null, 2, null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).kind()).isEqualTo(ArchiveItemKind.LINK);
        assertThat(response.items().get(0).attachment()).isNull();
        assertThat(response.items().get(0).link().url()).isEqualTo("https://a.example/1");
        assertThat(response.items().get(1).link().url()).isEqualTo("https://a.example/2");
        ArchiveCursor cursor = codec.decode(response.nextCursor(), conversationId, ArchiveKind.LINK);
        assertThat(cursor.seq()).isEqualTo(seq);
        assertThat(cursor.sub()).isEqualTo("2");
    }

    @Test
    void linkPageReturnsAllLinksFromTheOnlyMessageWithoutACursor() {
        stubMember();
        when(archiveRepository.findLinkMessagePage(eq(callerId), eq(conversationId), isNull(), isNull(), isNull(),
                isNull(), any(Pageable.class))).thenReturn(List.of(
                linkRow(7, "see https://a.example/x.")));

        ArchiveResponse response = service.browse(
                callerId, conversationId, "LINK", null, null, null, 30, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).link().url()).isEqualTo("https://a.example/x");
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void linkPageSkipsUrlLikeMessagesTheExtractorRejects() {
        stubMember();
        Pageable page = org.springframework.data.domain.PageRequest.of(0, 4);
        when(archiveRepository.findLinkMessagePage(eq(callerId), eq(conversationId), isNull(), isNull(), isNull(),
                isNull(), eq(page))).thenReturn(List.of(
                linkRow(10, "http://"), linkRow(9, "https://."),
                linkRow(8, "http:///path"), linkRow(7, "http://-")));
        when(archiveRepository.findLinkMessagePage(eq(callerId), eq(conversationId), isNull(), isNull(), isNull(),
                eq(6L), eq(page))).thenReturn(List.of());

        ArchiveResponse response = service.browse(
                callerId, conversationId, "LINK", null, null, null, 3, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.nextCursor()).isNull();
        verify(archiveRepository, times(2)).findLinkMessagePage(
                eq(callerId), eq(conversationId), isNull(), isNull(), isNull(), any(), any(Pageable.class));
    }

    // --------------------------------------------------------------- helpers

    private void stubMember() {
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, callerId))
                .thenReturn(true);
    }

    private static void assertValidation(ThrowingCallable action) {
        ApiException error = catchThrowableOfType(action, ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    private ArchiveLinkRow linkRow(long seq, String content) {
        return new FakeLinkRow(UUID.randomUUID(), seq, UUID.randomUUID(), Instant.EPOCH, content);
    }

    private ArchiveAttachmentRow attachmentRow(long seq, String mime, UUID attachmentId) {
        return new FakeAttachmentRow(UUID.randomUUID(), seq, UUID.randomUUID(), Instant.EPOCH,
                attachmentId, mime);
    }

    private record FakeLinkRow(UUID messageId, Long seq, UUID senderId, Instant createdAt,
                               String content) implements ArchiveLinkRow {
        @Override
        public UUID getMessageId() {
            return messageId;
        }

        @Override
        public Long getSeq() {
            return seq;
        }

        @Override
        public UUID getSenderId() {
            return senderId;
        }

        @Override
        public Instant getCreatedAt() {
            return createdAt;
        }

        @Override
        public String getContent() {
            return content;
        }
    }

    private record FakeAttachmentRow(UUID messageId, Long seq, UUID senderId, Instant createdAt,
                                     UUID attachmentId, String mimeType) implements ArchiveAttachmentRow {
        @Override
        public UUID getMessageId() {
            return messageId;
        }

        @Override
        public Long getSeq() {
            return seq;
        }

        @Override
        public UUID getSenderId() {
            return senderId;
        }

        @Override
        public Instant getCreatedAt() {
            return createdAt;
        }

        @Override
        public UUID getAttachmentId() {
            return attachmentId;
        }

        @Override
        public String getFileName() {
            return "file.bin";
        }

        @Override
        public String getMimeType() {
            return mimeType;
        }

        @Override
        public Long getSizeBytes() {
            return 42L;
        }

        @Override
        public Integer getDurationMs() {
            return 1234;
        }

        @Override
        public String getBlurHash() {
            return "LEHV6nWB2yk8";
        }

        @Override
        public Integer getWidth() {
            return 10;
        }

        @Override
        public Integer getHeight() {
            return 20;
        }
    }
}

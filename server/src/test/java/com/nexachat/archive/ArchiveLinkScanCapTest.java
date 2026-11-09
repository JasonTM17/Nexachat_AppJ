package com.nexachat.archive;

import com.nexachat.archive.dto.ArchiveLinkRow;
import com.nexachat.archive.dto.ArchiveResponse;
import com.nexachat.auth.JwtProperties;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * The bounded LINK scan must still return a continuation cursor when the scan
 * budget is exhausted before any valid link is found. Otherwise all older
 * links in the conversation become unreachable for every member.
 */
@ExtendWith(MockitoExtension.class)
class ArchiveLinkScanCapTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private ArchiveRepository archiveRepository;

    private ArchiveService service;

    private final UUID callerId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ArchiveService(conversationRepository, memberRepository, archiveRepository,
                new ArchiveCursorCodec(new JwtProperties(
                        "test-only-secret-0123456789abcdef-0123456789", 900, 14)));
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, callerId))
                .thenReturn(true);
    }

    @Test
    void exhaustedLinkScanWithoutAValidLinkStillReturnsACursor() {
        // Every row matches the SQL prefilter (http://) but is rejected by the
        // strict extractor, so the service keeps scanning until its chunk cap.
        List<ArchiveLinkRow> rejected = IntStream.range(0, 51)
                .mapToObj(offset -> (ArchiveLinkRow) new RejectedLinkRow(
                        UUID.randomUUID(), 100L - offset, UUID.randomUUID(), Instant.EPOCH, "http://"))
                .toList();
        when(archiveRepository.findLinkMessagePage(eq(conversationId), isNull(), isNull(), isNull(),
                any(), any(Pageable.class))).thenReturn(rejected);

        ArchiveResponse response = service.browse(
                callerId, conversationId, "LINK", null, null, null, 10, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.nextCursor())
                .as("a truncated scan must stay resumable; null silently hides older links")
                .isNotNull();
    }

    private record RejectedLinkRow(UUID messageId, Long seq, UUID senderId, Instant createdAt,
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
}

package com.nexachat.archive;

import com.nexachat.archive.dto.ArchiveAttachmentRow;
import com.nexachat.archive.dto.ArchiveAttachmentView;
import com.nexachat.archive.dto.ArchiveItem;
import com.nexachat.archive.dto.ArchiveLinkRow;
import com.nexachat.archive.dto.ArchiveLinkView;
import com.nexachat.archive.dto.ArchiveResponse;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Storage browser ("Kho lưu trữ") use case: one member-scoped, newest-first
 * page of media, files or links for a conversation.
 *
 * <p><b>Authorization:</b> the conversation must exist and the caller must be a
 * member, checked on every request before the cursor is even decoded. The
 * cursor is a keyset position only — it can never widen access.
 *
 * <p><b>Paging:</b> the keyset is the global message {@code seq} (plus the
 * attachment id inside one message, or a link offset inside one message).
 * Because rows are read newest-first with strict {@code <} comparisons, a
 * message inserted while the client is paging gets a higher seq and therefore
 * cannot shift, duplicate or skip an older page.
 *
 * <p><b>Exclusions:</b> tombstoned messages are dropped in SQL; voice notes
 * ({@code messages.type = 'AUDIO'}) are excluded from every tab, so they never
 * leak into MEDIA or FILE.
 */
@Service
public class ArchiveService {

    static final int DEFAULT_LIMIT = 30;
    static final int MAX_LIMIT = 50;

    /**
     * Safety bound for a link page that must skip TEXT messages whose URL-like
     * token the strict extractor rejects. Each iteration is one bounded query,
     * never a per-item lookup; a pathological conversation returns a partial
     * page plus a cursor instead of scanning without end.
     */
    static final int MAX_LINK_SCAN_CHUNKS = 20;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final ArchiveRepository archiveRepository;
    private final ArchiveCursorCodec cursorCodec;

    public ArchiveService(ConversationRepository conversationRepository,
                          ConversationMemberRepository memberRepository,
                          ArchiveRepository archiveRepository,
                          ArchiveCursorCodec cursorCodec) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.archiveRepository = archiveRepository;
        this.cursorCodec = cursorCodec;
    }

    /**
     * @throws ApiException {@code VALIDATION_FAILED} for an unknown type, an
     *                      out-of-range limit, {@code from > to}, a malformed
     *                      timestamp or a tampered cursor;
     *                      {@code CONVERSATION_NOT_FOUND}; {@code NOT_MEMBER}
     */
    @Transactional(readOnly = true)
    public ArchiveResponse browse(UUID userId, UUID conversationId, String type, UUID senderId,
                                  String from, String to, Integer limit, String cursor) {
        ArchiveKind kind = ArchiveKind.parse(type);
        int pageSize = normalizeLimit(limit);
        Instant fromTs = parseInstant(from, "from");
        Instant toTs = parseInstant(to, "to");
        if (fromTs != null && toTs != null && fromTs.isAfter(toTs)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "from must not be after to");
        }

        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
        ArchiveCursor position = cursorCodec.decode(cursor, conversationId, kind);
        return switch (kind) {
            case MEDIA, FILE -> attachmentPage(conversationId, senderId, fromTs, toTs, pageSize, kind, position);
            case LINK -> linkPage(conversationId, senderId, fromTs, toTs, pageSize, position);
        };
    }

    // ------------------------------------------------------------ attachments

    private ArchiveResponse attachmentPage(UUID conversationId, UUID senderId, Instant fromTs,
                                           Instant toTs, int pageSize, ArchiveKind kind,
                                           ArchiveCursor position) {
        Long cursorSeq = position == null ? null : position.seq();
        UUID cursorAttachmentId = position == null ? null : UUID.fromString(position.sub());
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<ArchiveAttachmentRow> rows = kind == ArchiveKind.MEDIA
                ? archiveRepository.findMediaPage(conversationId, senderId, fromTs, toTs,
                        cursorSeq, cursorAttachmentId, page)
                : archiveRepository.findFilePage(conversationId, senderId, fromTs, toTs,
                        cursorSeq, cursorAttachmentId, page);

        boolean hasMore = rows.size() > pageSize;
        List<ArchiveAttachmentRow> visible = hasMore ? rows.subList(0, pageSize) : rows;
        List<ArchiveItem> items = visible.stream()
                .map(ArchiveService::attachmentItem)
                .toList();
        String nextCursor = null;
        if (hasMore && !visible.isEmpty()) {
            ArchiveAttachmentRow last = visible.get(visible.size() - 1);
            nextCursor = cursorCodec.encode(
                    conversationId, kind, last.getSeq(), last.getAttachmentId().toString());
        }
        return new ArchiveResponse(items, nextCursor);
    }

    private static ArchiveItem attachmentItem(ArchiveAttachmentRow row) {
        ArchiveItemKind kind = itemKindForMime(row.getMimeType());
        ArchiveAttachmentView attachment = new ArchiveAttachmentView(
                row.getAttachmentId(),
                row.getFileName(),
                row.getMimeType(),
                row.getSizeBytes(),
                row.getDurationMs(),
                row.getBlurHash(),
                row.getWidth(),
                row.getHeight());
        return new ArchiveItem(
                row.getMessageId(),
                row.getSeq(),
                row.getSenderId(),
                row.getCreatedAt(),
                kind,
                attachment,
                null);
    }

    private static ArchiveItemKind itemKindForMime(String mimeType) {
        if (mimeType.startsWith("image/")) {
            return ArchiveItemKind.IMAGE;
        }
        if (mimeType.startsWith("video/")) {
            return ArchiveItemKind.VIDEO;
        }
        return ArchiveItemKind.FILE;
    }

    // ------------------------------------------------------------------ links

    private ArchiveResponse linkPage(UUID conversationId, UUID senderId, Instant fromTs,
                                     Instant toTs, int pageSize, ArchiveCursor position) {
        List<ArchiveItem> items = new ArrayList<>(pageSize);
        int startOffset = 0;
        boolean applyOffset = false;
        Long maxSeq = null;
        if (position != null) {
            startOffset = Integer.parseInt(position.sub());
            applyOffset = startOffset >= 0;
            maxSeq = applyOffset ? position.seq() : position.seq() - 1;
        }

        long lastScannedSeq = 0;
        for (int chunk = 0; chunk < MAX_LINK_SCAN_CHUNKS; chunk++) {
            List<ArchiveLinkRow> rows = archiveRepository.findLinkMessagePage(
                    conversationId, senderId, fromTs, toTs, maxSeq,
                    PageRequest.of(0, pageSize + 1));
            if (rows.isEmpty()) {
                return new ArchiveResponse(List.copyOf(items), null);
            }
            for (ArchiveLinkRow row : rows) {
                lastScannedSeq = row.getSeq();
                List<String> links = ArchiveLinkExtractor.extract(row.getContent());
                int start = 0;
                if (applyOffset && row.getSeq() == position.seq()) {
                    start = Math.min(startOffset, links.size());
                }
                for (int i = start; i < links.size(); i++) {
                    if (items.size() == pageSize) {
                        String next = cursorCodec.encode(conversationId, ArchiveKind.LINK,
                                row.getSeq(), Integer.toString(i));
                        return new ArchiveResponse(List.copyOf(items), next);
                    }
                    items.add(linkItem(row, links.get(i)));
                }
            }
            boolean moreMessages = rows.size() > pageSize;
            if (items.size() == pageSize) {
                String next = moreMessages
                        ? cursorCodec.encode(conversationId, ArchiveKind.LINK,
                                lastScannedSeq, "-1")
                        : null;
                return new ArchiveResponse(List.copyOf(items), next);
            }
            if (!moreMessages) {
                return new ArchiveResponse(List.copyOf(items), null);
            }
            applyOffset = false;
            maxSeq = lastScannedSeq - 1;
        }
        // The scan budget ran out before the conversation was exhausted, even
        // if every scanned message was rejected by the extractor: continue
        // below the last scanned sequence so older links stay reachable.
        String next = lastScannedSeq > 0
                ? cursorCodec.encode(conversationId, ArchiveKind.LINK, lastScannedSeq, "-1")
                : null;
        return new ArchiveResponse(List.copyOf(items), next);
    }

    private static ArchiveItem linkItem(ArchiveLinkRow row, String url) {
        return new ArchiveItem(
                row.getMessageId(),
                row.getSeq(),
                row.getSenderId(),
                row.getCreatedAt(),
                ArchiveItemKind.LINK,
                null,
                new ArchiveLinkView(url));
    }

    // -------------------------------------------------------------- validation

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private static Instant parseInstant(String raw, String parameter) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    parameter + " must be an ISO-8601 instant");
        }
    }
}

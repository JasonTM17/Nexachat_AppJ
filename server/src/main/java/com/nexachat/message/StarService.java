package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.message.dto.StarRef;
import com.nexachat.message.dto.StarredMessageView;
import com.nexachat.message.dto.StarredPageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-user starred messages (V38 safety): {@code PUT/DELETE
 * /messages/{id}/star} and the paged {@code GET /messages/starred} list.
 *
 * <p><b>Semantics:</b> a star is private to the starring user — unlike a pin
 * it fans no event out and carries no conversation-wide state. Both
 * mutations are idempotent by construction (the composite PK absorbs a
 * repeat star; deleting a missing row is a no-op), which keeps client
 * retries and offline outbox replays safe.
 *
 * <p><b>Authorization:</b> starring re-checks the read boundary at write
 * time — the viewer must be a current member who was admitted when the
 * message was created, and a peer-suppressed row is only starrable by its
 * sender. Listing re-checks it again at read time, so leaving a
 * conversation hides its starred rows without deleting the star.
 */
@Service
public class StarService {

    static final int MAX_PAGE_LIMIT = 100;
    static final int DEFAULT_PAGE_LIMIT = 50;

    private record Cursor(Instant starredAt, UUID messageId) {
    }

    private final MessageStarRepository starRepository;
    private final MessageRepository messageRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageService messageService;

    public StarService(MessageStarRepository starRepository,
                       MessageRepository messageRepository,
                       ConversationMemberRepository memberRepository,
                       MessageService messageService) {
        this.starRepository = starRepository;
        this.messageRepository = messageRepository;
        this.memberRepository = memberRepository;
        this.messageService = messageService;
    }

    /**
     * Stars {@code messageId} for {@code userId}. Idempotent: an existing
     * star returns success without change.
     *
     * @throws ApiException {@code MESSAGE_NOT_FOUND} (missing row, or a
     *                      suppressed row the viewer could not see),
     *                      {@code NOT_MEMBER} (not a member, or the message
     *                      predates the viewer's admission)
     */
    @Transactional
    public void star(UUID userId, UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        // A suppressed row is invisible to everyone but its sender — the
        // refusal is the same MESSAGE_NOT_FOUND a foreign id gives.
        if (message.isPeerSuppressed() && !message.getSender().getId().equals(userId)) {
            throw ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND, "Message not found");
        }
        UUID conversationId = message.getConversation().getId();
        if (!memberRepository.existsAdmittedAt(conversationId, userId, message.getCreatedAt())) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
        starRepository.insertIgnoringConflict(userId, messageId);
    }

    /** Removes the star; idempotent on a missing row. */
    @Transactional
    public void unstar(UUID userId, UUID messageId) {
        starRepository.deleteOne(userId, messageId);
    }

    /**
     * Newest-first keyset page of the viewer's starred messages. Refs page
     * first; rows are then re-fetched under the viewer's read boundary
     * (suppression + current membership) and zipped back in star order —
     * a star survives leaving a conversation but its row stops listing.
     */
    @Transactional(readOnly = true)
    public StarredPageResponse listStarred(UUID userId, Integer limit, String cursorToken) {
        int pageSize = normalizeLimit(limit);
        Cursor cursor = cursorToken == null ? null : decodeCursor(cursorToken);
        List<StarRef> refs = cursor == null
                ? starRepository.findStarredRefsFirst(userId, PageRequest.of(0, pageSize + 1))
                : starRepository.findStarredRefsAfter(userId, cursor.starredAt(), cursor.messageId(),
                        PageRequest.of(0, pageSize + 1));
        boolean hasMore = refs.size() > pageSize;
        List<StarRef> page = hasMore ? refs.subList(0, pageSize) : refs;
        if (page.isEmpty()) {
            return new StarredPageResponse(List.of(), null);
        }

        List<UUID> ids = page.stream().map(StarRef::messageId).toList();
        Map<UUID, MessageRow> rowsById = messageRepository.findStarredRowsByIdIn(ids, userId)
                .stream()
                .collect(Collectors.toMap(MessageRow::id, Function.identity()));
        Map<UUID, MessageResponse> hydrated = messageService
                .toResponses(rowsById.values().stream().toList(), userId)
                .stream()
                .collect(Collectors.toMap(MessageResponse::id, Function.identity()));

        List<StarredMessageView> items = page.stream()
                .map(ref -> hydrated.containsKey(ref.messageId())
                        ? new StarredMessageView(ref.starredAt(), hydrated.get(ref.messageId()))
                        : null)
                .filter(java.util.Objects::nonNull)
                .toList();
        String nextCursor = hasMore
                ? encodeCursor(page.get(page.size() - 1))
                : null;
        return new StarredPageResponse(items, nextCursor);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        return limit < 1 ? 1 : Math.min(limit, MAX_PAGE_LIMIT);
    }

    private static String encodeCursor(StarRef ref) {
        String value = "v1|" + ref.starredAt() + "|" + ref.messageId();
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
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid starred cursor");
        }
    }
}

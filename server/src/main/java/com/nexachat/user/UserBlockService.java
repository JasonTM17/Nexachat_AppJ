package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.dto.BlockedPageResponse;
import com.nexachat.user.dto.BlockedUserView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Block/unblock use cases (V38 safety): {@code POST/DELETE /users/{id}/block},
 * the paged {@code GET /users/blocked} list, and the per-peer probe.
 *
 * <p><b>Semantics:</b> a block is one-directional and private — the blocked
 * user is never told. Both mutations are idempotent by construction (the
 * composite PK absorbs a repeat block; deleting a missing edge is a no-op),
 * which keeps client retries and offline outbox replays safe.
 *
 * <p><b>Privacy contract:</b> nothing here touches the blocked side — no
 * notification, no event, no conversation mutation. Enforcement of the
 * block's effects (messages, calls, groups, presence, typing, avatars, push)
 * lives at each cross-cutting fan-out point and reads
 * {@link UserBlockRepository} directly.
 */
@Service
public class UserBlockService {

    private static final Logger log = LoggerFactory.getLogger(UserBlockService.class);

    static final int MAX_PAGE_LIMIT = 100;
    static final int DEFAULT_PAGE_LIMIT = 50;

    private record Cursor(Instant createdAt, UUID blockedId) {
    }

    private final UserBlockRepository blockRepository;
    private final UserRepository userRepository;

    public UserBlockService(UserBlockRepository blockRepository,
                            UserRepository userRepository) {
        this.blockRepository = blockRepository;
        this.userRepository = userRepository;
    }

    /**
     * Blocks {@code targetId} for {@code userId}. Idempotent: an existing
     * edge returns success without change.
     *
     * @throws ApiException {@code VALIDATION_FAILED} (self-block),
     *                      {@code USER_NOT_FOUND} (missing or deleted target)
     */
    @Transactional
    public void block(UUID userId, UUID targetId) {
        if (userId.equals(targetId)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Cannot block yourself");
        }
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (target.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        // The user_blocks FK checks take key-share on both user parents; take
        // them explicitly in UUID order so a concurrent block in the reverse
        // direction cannot complete a lock cycle (user -> user order).
        userRepository.lockForKeyShareSorted(List.of(userId, targetId));
        int inserted = blockRepository.insertIgnoringConflict(userId, targetId);
        if (inserted == 1) {
            log.info("User {} blocked {}", userId, targetId);
        }
    }

    /** Removes the block edge; idempotent on a missing edge. */
    @Transactional
    public void unblock(UUID userId, UUID targetId) {
        int removed = blockRepository.deleteOne(userId, targetId);
        if (removed == 1) {
            log.info("User {} unblocked {}", userId, targetId);
        }
    }

    /** Per-peer probe used by chat surfaces to render the blocked state. */
    @Transactional(readOnly = true)
    public boolean isBlocked(UUID userId, UUID targetId) {
        return blockRepository.existsByBlocker_IdAndBlocked_Id(userId, targetId);
    }

    /**
     * Newest-first keyset page of the caller's blocked users. The cursor is
     * opaque ({@code v1|createdAt|blockedId}, base64url) — malformed input is
     * a client bug and fails {@code VALIDATION_FAILED} like the inbox cursor.
     */
    @Transactional(readOnly = true)
    public BlockedPageResponse listBlocked(UUID userId, Integer limit, String cursorToken) {
        int pageSize = normalizeLimit(limit);
        Cursor cursor = cursorToken == null ? null : decodeCursor(cursorToken);
        List<BlockedUserView> rows = cursor == null
                ? blockRepository.findBlockedPageFirst(userId, PageRequest.of(0, pageSize + 1))
                : blockRepository.findBlockedPageAfter(userId, cursor.createdAt(), cursor.blockedId(),
                        PageRequest.of(0, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<BlockedUserView> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1))
                : null;
        return new BlockedPageResponse(page, nextCursor);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        return limit < 1 ? 1 : Math.min(limit, MAX_PAGE_LIMIT);
    }

    private static String encodeCursor(BlockedUserView row) {
        String value = "v1|" + row.blockedAt() + "|" + row.userId();
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
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid blocked cursor");
        }
    }
}

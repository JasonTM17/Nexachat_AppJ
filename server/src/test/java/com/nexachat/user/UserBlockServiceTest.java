package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.dto.BlockedPageResponse;
import com.nexachat.user.dto.BlockedUserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserBlockService} unit tests — the directed-edge use cases behind
 * {@code POST/DELETE /users/{id}/block} and {@code GET /users/blocked}.
 */
@ExtendWith(MockitoExtension.class)
class UserBlockServiceTest {

    @Mock
    private UserBlockRepository blockRepository;
    @Mock
    private UserRepository userRepository;

    private UserBlockService blockService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        blockService = new UserBlockService(blockRepository, userRepository);
    }

    private User activeTarget() {
        User user = mock(User.class);
        lenient().when(user.getDeletedAt()).thenReturn(null);
        return user;
    }

    // ------------------------------------------------------------------ block

    @Test
    void block_self_isValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> blockService.block(callerId, callerId));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(blockRepository, never()).insertIgnoringConflict(any(), any());
    }

    @Test
    void block_unknownTarget_isUserNotFound() {
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> blockService.block(callerId, targetId));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }

    @Test
    void block_deletedTarget_isUserNotFound() {
        User deleted = mock(User.class);
        when(deleted.getDeletedAt()).thenReturn(Instant.now());
        when(userRepository.findById(targetId)).thenReturn(Optional.of(deleted));
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> blockService.block(callerId, targetId));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }

    @Test
    void block_happy_locksParentsInOrderAndInserts() {
        User target = activeTarget();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(blockRepository.insertIgnoringConflict(callerId, targetId)).thenReturn(1);

        blockService.block(callerId, targetId);

        verify(userRepository).lockForKeyShareSorted(anyList());
        verify(blockRepository).insertIgnoringConflict(callerId, targetId);
    }

    @Test
    void block_repeat_isIdempotent() {
        User target = activeTarget();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(blockRepository.insertIgnoringConflict(callerId, targetId)).thenReturn(0);

        // No exception: the storage-level dedupe makes a re-block a no-op.
        blockService.block(callerId, targetId);
        verify(blockRepository).insertIgnoringConflict(callerId, targetId);
    }

    // ---------------------------------------------------------------- unblock

    @Test
    void unblock_removesTheEdgeIdempotently() {
        when(blockRepository.deleteOne(callerId, targetId)).thenReturn(0);
        blockService.unblock(callerId, targetId);   // no edge — still 204
        verify(blockRepository).deleteOne(callerId, targetId);
    }

    // ------------------------------------------------------------- listBlocked

    @Test
    void listBlocked_pagesNewestFirstWithCursor() {
        Instant t1 = Instant.parse("2026-11-15T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-14T10:00:00Z");
        // pageSize+1 rows → hasMore, cursor minted from the last returned row.
        List<BlockedUserView> rows = List.of(
                new BlockedUserView(UUID.randomUUID(), "A", "+8401", null, t1),
                new BlockedUserView(UUID.randomUUID(), "B", "+8402", null, t2),
                new BlockedUserView(UUID.randomUUID(), "C", "+8403", null, t2));
        when(blockRepository.findBlockedPageFirst(eq(callerId), any(Pageable.class)))
                .thenReturn(rows);

        BlockedPageResponse page = blockService.listBlocked(callerId, 2, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void listBlocked_shortPage_hasNoCursor() {
        when(blockRepository.findBlockedPageFirst(eq(callerId), any(Pageable.class)))
                .thenReturn(List.of(
                        new BlockedUserView(UUID.randomUUID(), "A", "+8401", null, Instant.now())));

        BlockedPageResponse page = blockService.listBlocked(callerId, null, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void listBlocked_badCursor_isValidationFailed() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> blockService.listBlocked(callerId, null, "not-a-cursor"));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }
}

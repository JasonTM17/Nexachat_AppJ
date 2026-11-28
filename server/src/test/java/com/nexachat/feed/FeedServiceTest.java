package com.nexachat.feed;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.feed.dto.AuthorProfileResponse;
import com.nexachat.feed.dto.CommentResponse;
import com.nexachat.feed.dto.CreateCommentRequest;
import com.nexachat.feed.dto.CreatePostRequest;
import com.nexachat.feed.dto.EditCommentRequest;
import com.nexachat.feed.dto.EditPostRequest;
import com.nexachat.feed.dto.PostPageResponse;
import com.nexachat.feed.dto.PostResponse;
import com.nexachat.streak.StreakService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private final FeedStore store = mock(FeedStore.class);
    private final FeedRateLimits rateLimits = mock(FeedRateLimits.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<StreakService> streakService = mock(ObjectProvider.class);
    private final FeedService service = new FeedService(store, rateLimits, streakService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createFailsWhenTheImageWasClaimedByAnotherPostInBetween() {
        UUID authorId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        when(store.findImage(imageId)).thenReturn(Optional.of(new FeedStore.ImageRow(
                imageId, authorId, "key", "image/png", 4L, 16, 16, null)));
        when(store.insertPost(any(), any(), any(), any(), any(), any())).thenReturn(1);
        // A concurrent create bound the image after the bound-state check.
        when(store.bindImage(imageId, authorId)).thenReturn(0);

        assertThatThrownBy(() -> service.create(authorId,
                new CreatePostRequest("hi", imageId, UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .hasMessage("imageId is already attached to a post");
    }

    @Test
    void createFailsWithVideoMessageWhenVideoWasAlreadyBoundOrClaimedInBetween() {
        UUID authorId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        when(store.findImage(videoId)).thenReturn(Optional.of(new FeedStore.ImageRow(
                videoId, authorId, "feed/v.mp4", FeedImageService.VIDEO_MIME,
                1024L, null, null, NOW.minusSeconds(30))));
        when(store.findOwnPostByClientId(eq(authorId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(authorId,
                new CreatePostRequest("caption", null, UUID.randomUUID(), videoId)))
                .isInstanceOf(ApiException.class)
                .hasMessage("videoId is already attached to a post");
        verify(rateLimits, never()).consume(anyString(), any(Long.class), any(), anyString());

        when(store.findImage(videoId)).thenReturn(Optional.of(new FeedStore.ImageRow(
                videoId, authorId, "feed/v.mp4", FeedImageService.VIDEO_MIME,
                1024L, null, null, null)));
        when(store.insertPost(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(store.bindImage(videoId, authorId)).thenReturn(0);

        assertThatThrownBy(() -> service.create(authorId,
                new CreatePostRequest("caption", null, UUID.randomUUID(), videoId)))
                .isInstanceOf(ApiException.class)
                .hasMessage("videoId is already attached to a post");
    }

    @Test
    void deadPostReplayReturnsPostNotFoundWithoutBurningRateLimit() {
        UUID authorId = UUID.randomUUID();
        UUID clientPostId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        when(store.findImage(imageId)).thenReturn(Optional.of(new FeedStore.ImageRow(
                imageId, authorId, "feed/img.png", "image/png", 10L, 16, 16, NOW.minusSeconds(60))));
        when(store.existsDeadPostByClientId(authorId, clientPostId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(authorId,
                new CreatePostRequest("deleted", imageId, clientPostId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.POST_NOT_FOUND));

        verify(rateLimits, never()).consume(anyString(), any(Long.class), any(), anyString());
        verify(store, never()).insertPost(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deadCommentReplayReturnsCommentNotFoundWithoutBurningRateLimit() {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID clientCommentId = UUID.randomUUID();
        when(store.findVisiblePost(authorId, postId)).thenReturn(Optional.of(postRow(postId, authorId, "post", null)));
        when(store.existsDeadOrCrossPostCommentByClientId(authorId, postId, clientCommentId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createComment(authorId, postId,
                new CreateCommentRequest("hello", clientCommentId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND));

        verify(rateLimits, never()).consume(anyString(), any(Long.class), any(), anyString());
        verify(store, never()).insertComment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void editPostValidatesBodyOwnershipVisibilityAndStampsEditedAt() {
        UUID authorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();

        assertThatThrownBy(() -> service.editPost(authorId, postId, new EditPostRequest("   ")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));

        when(store.findVisiblePost(otherId, postId))
                .thenReturn(Optional.of(postRow(postId, authorId, "old", null)));
        assertThatThrownBy(() -> service.editPost(otherId, postId, new EditPostRequest("new")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.FORBIDDEN));

        when(store.findVisiblePost(authorId, postId))
                .thenReturn(Optional.of(postRow(postId, authorId, "old", null)))
                .thenReturn(Optional.of(postRow(postId, authorId, "trimmed", NOW)));
        when(store.updatePostBody(postId, authorId, "trimmed", NOW)).thenReturn(1);
        when(store.reactionsFor(postId, authorId)).thenReturn(List.of());

        PostResponse updated = service.editPost(authorId, postId, new EditPostRequest("  trimmed  "));
        assertThat(updated.body()).isEqualTo("trimmed");
        assertThat(updated.editedAt()).isEqualTo(NOW);
        verify(rateLimits).consume(eq("feed:edit:minute:" + authorId),
                eq((long) FeedService.EDITS_PER_MINUTE), any(), anyString());
    }

    @Test
    void editCommentValidatesBodyOwnershipVisibilityAndStampsEditedAt() {
        UUID authorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();

        assertThatThrownBy(() -> service.editComment(authorId, commentId, new EditCommentRequest("  ")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));

        when(store.findVisibleComment(otherId, commentId))
                .thenReturn(Optional.of(commentRow(commentId, postId, authorId, "old", null)));
        assertThatThrownBy(() -> service.editComment(otherId, commentId, new EditCommentRequest("new")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.FORBIDDEN));

        when(store.findVisibleComment(authorId, commentId))
                .thenReturn(Optional.of(commentRow(commentId, postId, authorId, "old", null)))
                .thenReturn(Optional.of(commentRow(commentId, postId, authorId, "edited", NOW)));
        when(store.updateCommentBody(commentId, authorId, "edited", NOW)).thenReturn(1);

        CommentResponse updated = service.editComment(authorId, commentId,
                new EditCommentRequest("  edited  "));
        assertThat(updated.body()).isEqualTo("edited");
        assertThat(updated.editedAt()).isEqualTo(NOW);
    }

    @Test
    void getAuthorProfileReturnsProfileOrThrowsUserNotFound() {
        UUID viewerId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        when(store.findVisibleAuthorProfile(viewerId, authorId))
                .thenReturn(Optional.of(new FeedStore.AuthorProfileRow(
                        authorId, "Alice", "/api/v1/files/avatars/a.png", 4L)));

        AuthorProfileResponse profile = service.getAuthorProfile(viewerId, authorId);
        assertThat(profile.userId()).isEqualTo(authorId);
        assertThat(profile.displayName()).isEqualTo("Alice");
        assertThat(profile.postCount()).isEqualTo(4L);

        UUID hiddenId = UUID.randomUUID();
        when(store.findVisibleAuthorProfile(viewerId, hiddenId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAuthorProfile(viewerId, hiddenId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.USER_NOT_FOUND));
    }

    @Test
    void listNormalizesAndEscapesSearchQuery() {
        UUID viewerId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        when(store.listPosts(eq(viewerId), eq(authorId), any(), any(), anyInt())).thenReturn(List.of());
        when(store.reactionsFor(List.<UUID>of(), viewerId)).thenReturn(Map.of());

        assertThatThrownBy(() -> service.list(viewerId, 10, null, authorId, "x".repeat(101)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));

        PostPageResponse blank = service.list(viewerId, 10, null, authorId, "   ");
        assertThat(blank.items()).isEmpty();
        verify(store).listPosts(eq(viewerId), eq(authorId), isNull(), isNull(), eq(11));

        service.list(viewerId, 10, null, authorId, "  100%_a\\b  ");
        verify(store).listPosts(eq(viewerId), eq(authorId), eq("%100\\%\\_a\\\\%".replace("\\\\", "\\\\").replace("%100\\%\\_a\\\\%", "%100\\%\\_a\\\\b%")), isNull(), eq(11));
    }

    @Test
    void nulBytesAreRejectedWithValidationFailedAcrossTextAndSearch() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(userId,
                new CreatePostRequest("hello\u0000world", null, UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.editPost(userId, postId,
                new EditPostRequest("edit\u0000post")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));

        when(store.findVisiblePost(userId, postId))
                .thenReturn(Optional.of(postRow(postId, userId, "ok", null)));
        assertThatThrownBy(() -> service.createComment(userId, postId,
                new CreateCommentRequest("hello\u0000comment", UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.editComment(userId, commentId,
                new EditCommentRequest("edit\u0000comment")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.list(userId, 10, null, null, "bad\u0000query"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }

    private static FeedStore.PostRow postRow(UUID id, UUID authorId, String body, Instant editedAt) {
        return new FeedStore.PostRow(id, authorId, "Author", null, body,
                null, null, null, null, null, 0, NOW.minusSeconds(10), editedAt);
    }

    private static FeedStore.CommentRow commentRow(UUID id, UUID postId, UUID authorId,
                                                   String body, Instant editedAt) {
        return new FeedStore.CommentRow(id, postId, authorId, "Author", null,
                body, NOW.minusSeconds(10), editedAt);
    }
}

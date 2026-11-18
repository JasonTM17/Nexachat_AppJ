package com.nexachat.feed;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.ReactionEmoji;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.feed.dto.AuthorResponse;
import com.nexachat.feed.dto.CommentPageResponse;
import com.nexachat.feed.dto.CommentResponse;
import com.nexachat.feed.dto.CreateCommentRequest;
import com.nexachat.feed.dto.CreatePostRequest;
import com.nexachat.feed.dto.ImageResponse;
import com.nexachat.feed.dto.PostPageResponse;
import com.nexachat.feed.dto.PostResponse;
import com.nexachat.feed.dto.ReactionListResponse;
import com.nexachat.feed.dto.ReactionSummary;
import com.nexachat.streak.StreakService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public-feed use cases: posts, flat comments and post reactions.
 *
 * <p><b>Visibility.</b> Every read and every mutation resolves the target
 * through the same predicate (not deleted, not hidden, author alive, no block
 * in either direction), so a post the caller may not see is reported as
 * {@code POST_NOT_FOUND} rather than as a forbidden action.
 *
 * <p><b>Idempotency.</b> Creates carry a client id and rely on the
 * {@code (author, client id)} unique constraint: a replay returns the stored
 * row with {@code created=false}, so an offline outbox retry can never
 * duplicate a post or a comment.
 *
 * <p><b>Rate limits.</b> Posts 3/min and 30/day, comments 10/min and 200/day,
 * reactions 60/min, all per user through {@link RateLimitService}; a denial is
 * {@code RATE_LIMITED} with {@code retryAfterSeconds}.
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    static final int MAX_BODY = 1000;
    static final int MAX_COMMENT = 500;
    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 50;
    static final int DEFAULT_COMMENT_PAGE = 30;
    static final int MAX_COMMENT_PAGE = 50;

    static final int POSTS_PER_MINUTE = 3;
    static final int POSTS_PER_DAY = 30;
    static final int COMMENTS_PER_MINUTE = 10;
    static final int COMMENTS_PER_DAY = 200;
    static final int REACTIONS_PER_MINUTE = 60;

    /** Feed images are served only through this path, never through {@code /files/**}. */
    static final String IMAGE_PATH = "/api/v1/feed/images/";

    /** Outcome of a create: the row plus whether this call stored it. */
    public record Result(PostResponse post, boolean created) {
    }

    /** Outcome of a comment create. */
    public record CommentResult(CommentResponse comment, boolean created) {
    }

    private final FeedStore store;
    private final RateLimitService rateLimitService;
    private final ObjectProvider<StreakService> streakService;
    private final Clock clock;

    public FeedService(FeedStore store,
                       RateLimitService rateLimitService,
                       ObjectProvider<StreakService> streakService,
                       Clock clock) {
        this.store = store;
        this.rateLimitService = rateLimitService;
        this.streakService = streakService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- posts

    /** Newest-first keyset page of the public feed. */
    @Transactional(readOnly = true)
    public PostPageResponse list(UUID viewerId, Integer limit, String cursorToken) {
        int pageSize = normalizeLimit(limit, DEFAULT_PAGE, MAX_PAGE);
        FeedCursor.Position cursor = cursorToken == null ? null : FeedCursor.decode(cursorToken);
        List<FeedStore.PostRow> rows = cursor == null
                ? store.listPostsFirst(viewerId, pageSize + 1)
                : store.listPostsAfter(viewerId, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<FeedStore.PostRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<UUID, List<FeedStore.ReactionRow>> reactions = store.reactionsFor(
                page.stream().map(FeedStore.PostRow::id).toList(), viewerId);
        String nextCursor = hasMore && !page.isEmpty()
                ? FeedCursor.encode(new FeedCursor.Position(
                        page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()))
                : null;
        return new PostPageResponse(
                page.stream().map(row -> toResponse(row, viewerId,
                        reactions.getOrDefault(row.id(), List.of()))).toList(),
                nextCursor);
    }

    /** One visible post. */
    @Transactional(readOnly = true)
    public PostResponse get(UUID viewerId, UUID postId) {
        FeedStore.PostRow row = requireVisiblePost(viewerId, postId);
        return toResponse(row, viewerId, store.reactionsFor(postId, viewerId));
    }

    /**
     * Creates a post, or returns the row an earlier attempt with the same
     * {@code clientPostId} stored.
     */
    @Transactional
    public Result create(UUID authorId, CreatePostRequest request) {
        consume("feed:post:minute:" + authorId, POSTS_PER_MINUTE, Duration.ofMinutes(1),
                "Too many posts");
        consume("feed:post:day:" + authorId, POSTS_PER_DAY, Duration.ofDays(1),
                "Daily post limit reached");

        String body = normalizeBody(request.body(), MAX_BODY, "body");
        UUID imageId = request.imageId();
        if (body == null && imageId == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "A post needs text or an image");
        }
        if (imageId != null) {
            FeedStore.ImageRow image = store.findImage(imageId)
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                            "imageId is not an uploaded feed image"));
            if (!image.ownerId().equals(authorId)) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "imageId is not an uploaded feed image");
            }
            if (image.boundAt() != null) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "imageId is already attached to a post");
            }
        }

        Instant now = clock.instant();
        int inserted = store.insertPost(UUID.randomUUID(), authorId, request.clientPostId(),
                body, imageId, now);
        if (inserted == 0) {
            FeedStore.PostRow existing = store.findOwnPostByClientId(authorId, request.clientPostId())
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.POST_NOT_FOUND,
                            "Post not found"));
            return new Result(toResponse(existing, authorId,
                    store.reactionsFor(existing.id(), authorId)), false);
        }
        if (imageId != null) {
            store.bindImage(imageId, authorId);
        }
        FeedStore.PostRow created = store.findOwnPostByClientId(authorId, request.clientPostId())
                .orElseThrow(() -> ApiException.of(ApiErrorCode.INTERNAL_ERROR,
                        "Post insert/read-back inconsistency"));
        recordSent(authorId, now);
        log.info("Feed post {} created by {}", created.id(), authorId);
        return new Result(toResponse(created, authorId, List.of()), true);
    }

    /** Soft-deletes the caller's own post; a post they cannot see is a 404. */
    @Transactional
    public void deletePost(UUID authorId, UUID postId) {
        if (store.softDeletePost(postId, authorId, clock.instant()) == 1) {
            log.info("Feed post {} deleted by its author", postId);
            return;
        }
        store.findVisiblePostAuthor(postId)
                .filter(author -> !author.equals(authorId))
                .ifPresent(author -> {
                    throw ApiException.of(ApiErrorCode.FORBIDDEN, "Not the author of this post");
                });
        throw ApiException.of(ApiErrorCode.POST_NOT_FOUND, "Post not found");
    }

    // ---------------------------------------------------------------- comments

    /** Oldest-first keyset page of one post's comments. */
    @Transactional(readOnly = true)
    public CommentPageResponse comments(UUID viewerId, UUID postId, Integer limit,
                                        String cursorToken) {
        requireVisiblePost(viewerId, postId);
        int pageSize = normalizeLimit(limit, DEFAULT_COMMENT_PAGE, MAX_COMMENT_PAGE);
        FeedCursor.Position cursor = cursorToken == null ? null : FeedCursor.decode(cursorToken);
        List<FeedStore.CommentRow> rows = cursor == null
                ? store.listCommentsFirst(viewerId, postId, pageSize + 1)
                : store.listCommentsAfter(viewerId, postId, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<FeedStore.CommentRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !page.isEmpty()
                ? FeedCursor.encode(new FeedCursor.Position(
                        page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()))
                : null;
        return new CommentPageResponse(
                page.stream().map(row -> toResponse(row, viewerId)).toList(), nextCursor);
    }

    /** Adds a comment, or returns the row an earlier attempt stored. */
    @Transactional
    public CommentResult createComment(UUID authorId, UUID postId, CreateCommentRequest request) {
        consume("feed:comment:minute:" + authorId, COMMENTS_PER_MINUTE, Duration.ofMinutes(1),
                "Too many comments");
        consume("feed:comment:day:" + authorId, COMMENTS_PER_DAY, Duration.ofDays(1),
                "Daily comment limit reached");

        requireVisiblePost(authorId, postId);
        String body = normalizeBody(request.body(), MAX_COMMENT, "body");
        if (body == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "body is required");
        }

        Instant now = clock.instant();
        int inserted = store.insertComment(UUID.randomUUID(), postId, authorId,
                request.clientCommentId(), body, now);
        if (inserted == 0) {
            FeedStore.CommentRow existing = store.findOwnCommentByClientId(
                            authorId, request.clientCommentId())
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.COMMENT_NOT_FOUND,
                            "Comment not found"));
            return new CommentResult(toResponse(existing, authorId), false);
        }
        store.incrementCommentCount(postId);
        recordSent(authorId, now);
        FeedStore.CommentRow created = store.findOwnCommentByClientId(
                        authorId, request.clientCommentId())
                .orElseThrow(() -> ApiException.of(ApiErrorCode.INTERNAL_ERROR,
                        "Comment insert/read-back inconsistency"));
        log.info("Feed comment {} created on post {} by {}", created.id(), postId, authorId);
        return new CommentResult(toResponse(created, authorId), true);
    }

    /** Soft-deletes the caller's own comment. */
    @Transactional
    public void deleteComment(UUID authorId, UUID commentId) {
        UUID postId = store.findCommentPostId(commentId).orElse(null);
        if (store.softDeleteComment(commentId, authorId, clock.instant()) == 1) {
            if (postId != null) {
                store.decrementCommentCount(postId);
            }
            log.info("Feed comment {} deleted by its author", commentId);
            return;
        }
        store.findVisibleCommentAuthor(commentId)
                .filter(author -> !author.equals(authorId))
                .ifPresent(author -> {
                    throw ApiException.of(ApiErrorCode.FORBIDDEN,
                            "Not the author of this comment");
                });
        throw ApiException.of(ApiErrorCode.COMMENT_NOT_FOUND, "Comment not found");
    }

    // --------------------------------------------------------------- reactions

    /** Idempotent add or remove of one emoji; returns the post's aggregate. */
    @Transactional
    public ReactionListResponse react(UUID userId, UUID postId, String rawEmoji, boolean add) {
        consume("feed:reaction:minute:" + userId, REACTIONS_PER_MINUTE, Duration.ofMinutes(1),
                "Too many reactions");
        requireVisiblePost(userId, postId);
        String emoji = ReactionEmoji.normalize(rawEmoji);
        if (add) {
            store.addReaction(postId, userId, emoji);
        } else {
            store.removeReaction(postId, userId, emoji);
        }
        return new ReactionListResponse(store.reactionsFor(postId, userId).stream()
                .map(row -> new ReactionSummary(row.emoji(), row.count(), row.mine()))
                .toList());
    }

    // ---------------------------------------------------------------- helpers

    private FeedStore.PostRow requireVisiblePost(UUID viewerId, UUID postId) {
        return store.findVisiblePost(viewerId, postId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.POST_NOT_FOUND, "Post not found"));
    }

    /** Feed activity counts toward the app-wide check-in streak. */
    private void recordSent(UUID userId, Instant at) {
        StreakService streaks = streakService.getIfAvailable();
        if (streaks != null) {
            streaks.recordUserSent(userId, at);
        }
    }

    private void consume(String bucket, long capacity, Duration period, String message) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                bucket, capacity, period);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, message,
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }

    private static int normalizeLimit(Integer limit, int fallback, int maximum) {
        if (limit == null) {
            return fallback;
        }
        return limit < 1 ? 1 : Math.min(limit, maximum);
    }

    private static String normalizeBody(String raw, int max, String field) {
        if (raw == null) {
            return null;
        }
        String body = raw.strip();
        if (body.isEmpty()) {
            return null;
        }
        if (body.length() > max) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    field + " must be at most " + max + " characters");
        }
        return body;
    }

    private static PostResponse toResponse(FeedStore.PostRow row, UUID viewerId,
                                           List<FeedStore.ReactionRow> reactions) {
        ImageResponse image = row.imageId() == null ? null
                : new ImageResponse(IMAGE_PATH + row.imageId(), row.imageWidth(),
                        row.imageHeight(), row.imageMime());
        List<ReactionSummary> summaries = new ArrayList<>(reactions.size());
        for (FeedStore.ReactionRow reaction : reactions) {
            summaries.add(new ReactionSummary(reaction.emoji(), reaction.count(), reaction.mine()));
        }
        return new PostResponse(row.id(),
                new AuthorResponse(row.authorId(), row.authorName(), row.avatarUrl()),
                row.body(), image, row.createdAt(), row.commentCount(), summaries,
                row.authorId().equals(viewerId));
    }

    private static CommentResponse toResponse(FeedStore.CommentRow row, UUID viewerId) {
        return new CommentResponse(row.id(), row.postId(),
                new AuthorResponse(row.authorId(), row.authorName(), row.avatarUrl()),
                row.body(), row.createdAt(), row.authorId().equals(viewerId));
    }
}

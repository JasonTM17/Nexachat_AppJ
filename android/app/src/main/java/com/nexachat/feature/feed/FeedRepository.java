package com.nexachat.feature.feed;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * Public feed: posts, flat comments, post reactions and the image upload that precedes a post.
 * Every call is caller-scoped; the server filters blocked authors in both directions.
 */
public interface FeedRepository {

    /** Public author identity; never carries phone or email. */
    record Author(@Nullable String id, @Nullable String displayName, @Nullable String avatarUrl) { }

    record Image(@Nullable String url, int width, int height, @Nullable String mimeType) { }

    record Reaction(@NonNull String emoji, int count, boolean mine) { }

    record Post(
            @NonNull String id,
            @NonNull Author author,
            @Nullable String body,
            @Nullable Image image,
            @Nullable String createdAt,
            int commentCount,
            @NonNull List<Reaction> reactions,
            boolean mine) { }

    record Comment(
            @NonNull String id,
            @NonNull String postId,
            @NonNull Author author,
            @Nullable String body,
            @Nullable String createdAt,
            boolean mine) { }

    /** One page of posts, newest first; {@code nextCursor} is null on the last page. */
    record PostPage(@NonNull List<Post> items, @Nullable String nextCursor) { }

    /** One page of comments, oldest first. */
    record CommentPage(@NonNull List<Comment> items, @Nullable String nextCursor) { }

    record UploadedImage(
            @NonNull String imageId,
            @Nullable String url,
            int width,
            int height,
            @Nullable String mimeType) { }

    Single<PostPage> listPosts(int limit, @Nullable String cursor);

    Single<Post> getPost(@NonNull String postId);

    /** {@code clientPostId} makes a retry return the row the server already stored. */
    Single<Post> createPost(@Nullable String body, @Nullable String imageId, @NonNull String clientPostId);

    Completable deletePost(@NonNull String postId);

    /** Adds or removes the caller's reaction and returns the new tally. */
    Single<List<Reaction>> setReaction(@NonNull String postId, @NonNull String emoji, boolean active);

    Single<CommentPage> listComments(@NonNull String postId, int limit, @Nullable String cursor);

    Single<Comment> addComment(
            @NonNull String postId, @NonNull String body, @NonNull String clientCommentId);

    Completable deleteComment(@NonNull String commentId);

    /** Uploads one picked image; the result is unbound until a post references it. */
    Single<UploadedImage> uploadImage(@NonNull Uri uri);
}

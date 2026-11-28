package com.nexachat.feature.feed;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * Public feed: posts, flat comments, post reactions, author profiles, and image/video uploads.
 * Every call is caller-scoped; the server filters blocked authors in both directions.
 */
public interface FeedRepository {

    /** Public author identity; never carries phone or email. */
    record Author(@Nullable String id, @Nullable String displayName, @Nullable String avatarUrl) { }

    record AuthorProfile(
            @NonNull String userId,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            long postCount) { }

    record SocialConfig(boolean webEnabled, boolean videoUploadsEnabled) { }

    record Image(@Nullable String url, int width, int height, @Nullable String mimeType) { }

    record Video(@Nullable String url, @Nullable String mimeType) { }

    record Reaction(@NonNull String emoji, int count, boolean mine) { }

    record Post(
            @NonNull String id,
            @NonNull Author author,
            @Nullable String body,
            @Nullable Image image,
            @Nullable Video video,
            @Nullable String createdAt,
            @Nullable String editedAt,
            int commentCount,
            @NonNull List<Reaction> reactions,
            boolean mine) {

        public Post(
                @NonNull String id,
                @NonNull Author author,
                @Nullable String body,
                @Nullable Image image,
                @Nullable String createdAt,
                int commentCount,
                @NonNull List<Reaction> reactions,
                boolean mine) {
            this(id, author, body, image, null, createdAt, null, commentCount, reactions, mine);
        }
    }

    record Comment(
            @NonNull String id,
            @NonNull String postId,
            @NonNull Author author,
            @Nullable String body,
            @Nullable String createdAt,
            @Nullable String editedAt,
            boolean mine) {

        public Comment(
                @NonNull String id,
                @NonNull String postId,
                @NonNull Author author,
                @Nullable String body,
                @Nullable String createdAt,
                boolean mine) {
            this(id, postId, author, body, createdAt, null, mine);
        }
    }

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

    record UploadedVideo(
            @NonNull String videoId,
            @Nullable String url,
            @Nullable String mimeType) { }

    default Single<PostPage> listPosts(int limit, @Nullable String cursor) {
        return listPosts(limit, cursor, null, null);
    }

    Single<PostPage> listPosts(
            int limit,
            @Nullable String cursor,
            @Nullable String authorId,
            @Nullable String q);

    Single<AuthorProfile> getAuthorProfile(@NonNull String authorId);

    Single<Post> getPost(@NonNull String postId);

    default Single<Post> createPost(
            @Nullable String body, @Nullable String imageId, @NonNull String clientPostId) {
        return createPost(body, imageId, null, clientPostId);
    }

    /** {@code clientPostId} makes a retry return the row the server already stored. */
    Single<Post> createPost(
            @Nullable String body,
            @Nullable String imageId,
            @Nullable String videoId,
            @NonNull String clientPostId);

    Single<Post> editPost(@NonNull String postId, @NonNull String body);

    Completable deletePost(@NonNull String postId);

    /** Adds or removes the caller's reaction and returns the new tally. */
    Single<List<Reaction>> setReaction(@NonNull String postId, @NonNull String emoji, boolean active);

    Single<CommentPage> listComments(@NonNull String postId, int limit, @Nullable String cursor);

    Single<Comment> addComment(
            @NonNull String postId, @NonNull String body, @NonNull String clientCommentId);

    Single<Comment> editComment(@NonNull String commentId, @NonNull String body);

    Completable deleteComment(@NonNull String commentId);

    /** Uploads one picked image; the result is unbound until a post references it. */
    Single<UploadedImage> uploadImage(@NonNull Uri uri);

    /** Uploads one picked MP4 video (<= 4 MiB); the result is unbound until a post references it. */
    Single<UploadedVideo> uploadVideo(@NonNull Uri uri);

    Single<SocialConfig> getSocialConfig();
}

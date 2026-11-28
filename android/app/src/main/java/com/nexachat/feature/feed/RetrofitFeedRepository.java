package com.nexachat.feature.feed;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.media.ImageCompressor;
import com.nexachat.core.network.ApiResult;
import com.nexachat.data.SessionOperationTracker;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;

/** REST implementation of {@link FeedRepository}. */
@Singleton
public final class RetrofitFeedRepository implements FeedRepository {

    private static final String PART_FILE = "file";
    private static final long MAX_IMAGE_BYTES = 25L * 1024L * 1024L;
    private static final int READ_BUFFER = 64 * 1024;
    /** The server accepts these for a feed image; anything else is refused before the upload. */
    private static final Set<String> ALLOWED_IMAGE_MIME =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final String VIDEO_MIME = "video/mp4";

    private final FeedApi api;
    private final Context context;
    private final ImageCompressor imageCompressor;
    private final SessionOperationTracker operationTracker;

    @Inject
    public RetrofitFeedRepository(
            @NonNull FeedApi api,
            @NonNull @ApplicationContext Context context,
            @NonNull ImageCompressor imageCompressor,
            @NonNull SessionOperationTracker operationTracker) {
        this.api = api;
        this.context = context.getApplicationContext();
        this.imageCompressor = imageCompressor;
        this.operationTracker = operationTracker;
    }

    @Override
    public Single<PostPage> listPosts(
            int limit,
            @Nullable String cursor,
            @Nullable String authorId,
            @Nullable String q) {
        String normalizedQ = FeedRules.normalizeSearchQuery(q);
        return operationTracker.track(
                requiredBody(api.listPosts(limit, cursor, authorId, normalizedQ)).map(page -> {
                    List<Post> items = new ArrayList<>(page.getItems().size());
                    for (FeedPostDto row : page.getItems()) {
                        if (row.getId() != null) {
                            items.add(toPost(row));
                        }
                    }
                    return new PostPage(List.copyOf(items), page.getNextCursor());
                }));
    }

    @Override
    public Single<AuthorProfile> getAuthorProfile(@NonNull String authorId) {
        return operationTracker.track(requiredBody(api.getAuthorProfile(authorId)).map(dto ->
                new AuthorProfile(
                        dto.getUserId() == null ? authorId : dto.getUserId(),
                        dto.getDisplayName(),
                        dto.getAvatarUrl(),
                        dto.resolvedPostCount())));
    }

    @Override
    public Single<Post> getPost(@NonNull String postId) {
        return operationTracker.track(requiredBody(api.getPost(postId)).map(RetrofitFeedRepository::toPost));
    }

    @Override
    public Single<Post> createPost(
            @Nullable String body,
            @Nullable String imageId,
            @Nullable String videoId,
            @NonNull String clientPostId) {
        return operationTracker.track(requiredBody(
                        api.createPost(new FeedCreatePostRequest(body, imageId, videoId, clientPostId)))
                .map(RetrofitFeedRepository::toPost));
    }

    @Override
    public Single<Post> editPost(@NonNull String postId, @NonNull String body) {
        return operationTracker.track(
                requiredBody(api.editPost(postId, new FeedEditPostRequest(body)))
                        .map(RetrofitFeedRepository::toPost));
    }

    @Override
    public Completable deletePost(@NonNull String postId) {
        return operationTracker.track(emptyBody(api.deletePost(postId)));
    }

    @Override
    public Single<List<Reaction>> setReaction(
            @NonNull String postId, @NonNull String emoji, boolean active) {
        Call<FeedReactionsDto> call = active
                ? api.addReaction(postId, emoji)
                : api.removeReaction(postId, emoji);
        return operationTracker.track(requiredBody(call).map(
                body -> toReactions(body.getReactions())));
    }

    @Override
    public Single<CommentPage> listComments(
            @NonNull String postId, int limit, @Nullable String cursor) {
        return operationTracker.track(
                requiredBody(api.listComments(postId, limit, cursor)).map(page -> {
                    List<Comment> items = new ArrayList<>(page.getItems().size());
                    for (FeedCommentDto row : page.getItems()) {
                        if (row.getId() != null) {
                            items.add(toComment(row, postId));
                        }
                    }
                    return new CommentPage(List.copyOf(items), page.getNextCursor());
                }));
    }

    @Override
    public Single<Comment> addComment(
            @NonNull String postId, @NonNull String body, @NonNull String clientCommentId) {
        return operationTracker.track(requiredBody(
                        api.createComment(postId, new FeedCreateCommentRequest(body, clientCommentId)))
                .map(row -> toComment(row, postId)));
    }

    @Override
    public Single<Comment> editComment(@NonNull String commentId, @NonNull String body) {
        return operationTracker.track(
                requiredBody(api.editComment(commentId, new FeedEditCommentRequest(body)))
                        .map(row -> toComment(row, row.getPostId() == null ? "" : row.getPostId())));
    }

    @Override
    public Completable deleteComment(@NonNull String commentId) {
        return operationTracker.track(emptyBody(api.deleteComment(commentId)));
    }

    /**
     * Reads the picked image, strips its location metadata and uploads the compressed bytes.
     * A public post must never carry the author's location, so any preparation failure aborts
     * the upload instead of falling back to the original bytes.
     */
    @Override
    public Single<UploadedImage> uploadImage(@NonNull Uri uri) {
        return Single.fromCallable(() -> prepare(uri))
                .subscribeOn(Schedulers.io())
                .flatMap(prepared -> requiredBody(api.uploadImage(prepared.part))
                        .doFinally(() -> deleteQuietly(prepared.temporaryFile)))
                .map(dto -> {
                    if (dto.getImageId() == null) {
                        throw new FeedApiException(200, "EMPTY_RESPONSE");
                    }
                    return new UploadedImage(
                            dto.getImageId(),
                            dto.getUrl(),
                            dto.resolvedWidth(),
                            dto.resolvedHeight(),
                            dto.getMimeType());
                });
    }

    @Override
    public Single<UploadedVideo> uploadVideo(@NonNull Uri uri) {
        return Single.fromCallable(() -> prepareVideo(uri))
                .subscribeOn(Schedulers.io())
                .flatMap(part -> requiredBody(api.uploadVideo(part)))
                .map(dto -> {
                    if (dto.getVideoId() == null) {
                        throw new FeedApiException(200, "EMPTY_RESPONSE");
                    }
                    return new UploadedVideo(
                            dto.getVideoId(),
                            dto.getUrl(),
                            dto.getMimeType());
                });
    }

    @Override
    public Single<SocialConfig> getSocialConfig() {
        return operationTracker.track(requiredBody(api.getSocialConfig()).map(dto ->
                new SocialConfig(dto.isWebEnabled(), dto.isVideoUploadsEnabled())));
    }

    private PreparedImage prepare(@NonNull Uri uri) throws IOException {
        byte[] original = readBounded(uri, MAX_IMAGE_BYTES, "picked image");
        String declared = context.getContentResolver().getType(uri);
        String mime = declared == null ? "image/jpeg" : declared.toLowerCase(Locale.ROOT);
        ImageCompressor.Result optimized;
        try {
            optimized = imageCompressor.compress(original, mime, null);
        } catch (IOException | RuntimeException failure) {
            throw new IOException("image could not be prepared for a public post", failure);
        }
        if (optimized == null || !ALLOWED_IMAGE_MIME.contains(optimized.mimeType())) {
            throw new IOException("unsupported image type");
        }
        MediaType mediaType = MediaType.parse(optimized.mimeType());
        File temporary = optimized.file();
        byte[] bytes = optimized.bytes();
        RequestBody body = bytes != null
                ? RequestBody.create(bytes, mediaType)
                : RequestBody.create(temporary, mediaType);
        return new PreparedImage(
                MultipartBody.Part.createFormData(PART_FILE, optimized.fileName(), body),
                temporary);
    }

    private MultipartBody.Part prepareVideo(@NonNull Uri uri) throws IOException {
        String declared = context.getContentResolver().getType(uri);
        if (declared != null && !FeedRules.isMp4Mime(declared)) {
            throw new FeedApiException(415, "MIME_NOT_ALLOWED");
        }
        byte[] bytes;
        try {
            bytes = readBounded(uri, FeedRules.MAX_VIDEO_BYTES, "picked video");
        } catch (FileTooLargeException tooLarge) {
            throw new FeedApiException(413, "FILE_TOO_LARGE");
        }
        if (!FeedRules.hasIsoBmffFtypBox(bytes)) {
            throw new FeedApiException(415, "MIME_NOT_ALLOWED");
        }
        RequestBody body = RequestBody.create(bytes, MediaType.parse(VIDEO_MIME));
        return MultipartBody.Part.createFormData(PART_FILE, "clip.mp4", body);
    }

    private byte[] readBounded(@NonNull Uri uri, long maxBytes, @NonNull String label)
            throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException(label + " is unreadable");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[READ_BUFFER];
            long total = 0L;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new FileTooLargeException(label + " is too large");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static Post toPost(FeedPostDto dto) {
        return new Post(
                dto.getId() == null ? "" : dto.getId(),
                toAuthor(dto.getAuthor()),
                dto.getBody(),
                toImage(dto.getImage()),
                toVideo(dto.getVideo()),
                dto.getCreatedAt(),
                dto.getEditedAt(),
                dto.resolvedCommentCount(),
                toReactions(dto.getReactions()),
                dto.isMine());
    }

    private static Comment toComment(FeedCommentDto dto, String fallbackPostId) {
        return new Comment(
                dto.getId() == null ? "" : dto.getId(),
                dto.getPostId() == null ? fallbackPostId : dto.getPostId(),
                toAuthor(dto.getAuthor()),
                dto.getBody(),
                dto.getCreatedAt(),
                dto.getEditedAt(),
                dto.isMine());
    }

    private static Author toAuthor(@Nullable FeedAuthorDto dto) {
        return dto == null
                ? new Author(null, null, null)
                : new Author(dto.getId(), dto.getDisplayName(), dto.getAvatarUrl());
    }

    @Nullable
    private static Image toImage(@Nullable FeedImageRefDto dto) {
        if (dto == null || dto.getUrl() == null) {
            return null;
        }
        return new Image(dto.getUrl(), dto.resolvedWidth(), dto.resolvedHeight(), dto.getMimeType());
    }

    @Nullable
    private static Video toVideo(@Nullable FeedVideoRefDto dto) {
        if (dto == null || dto.getUrl() == null) {
            return null;
        }
        return new Video(dto.getUrl(), dto.getMimeType());
    }

    private static List<Reaction> toReactions(List<FeedReactionDto> rows) {
        List<Reaction> reactions = new ArrayList<>(rows.size());
        for (FeedReactionDto row : rows) {
            if (row.getEmoji() != null && row.resolvedCount() > 0) {
                reactions.add(new Reaction(row.getEmoji(), row.resolvedCount(), row.isMine()));
            }
        }
        return List.copyOf(reactions);
    }

    private <T> Single<T> requiredBody(@NonNull Call<T> call) {
        return ApiResult.safeApiCall(call).flatMap(result -> {
            if (result instanceof ApiResult.Success<?> success) {
                Object body = success.getBody();
                if (body == null) {
                    return Single.error(new FeedApiException(200, "EMPTY_RESPONSE"));
                }
                @SuppressWarnings("unchecked")
                T value = (T) body;
                return Single.just(value);
            }
            if (result instanceof ApiResult.HttpError<?> http) {
                return Single.error(new FeedApiException(http.getStatus(), http.getErrorCode()));
            }
            return Single.error(((ApiResult.NetworkError<?>) result).getCause());
        });
    }

    private Completable emptyBody(@NonNull Call<?> call) {
        return ApiResult.safeApiCall(call).flatMapCompletable(result -> {
            if (result instanceof ApiResult.Success<?>) {
                return Completable.complete();
            }
            if (result instanceof ApiResult.HttpError<?> http) {
                return Completable.error(new FeedApiException(http.getStatus(), http.getErrorCode()));
            }
            return Completable.error(((ApiResult.NetworkError<?>) result).getCause());
        });
    }

    private static final class FileTooLargeException extends IOException {
        FileTooLargeException(String message) {
            super(message);
        }
    }

    /** One prepared multipart upload plus the cache file that must be removed afterwards. */
    private record PreparedImage(MultipartBody.Part part, @Nullable File temporaryFile) { }
}

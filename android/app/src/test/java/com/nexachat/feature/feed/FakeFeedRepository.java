package com.nexachat.feature.feed;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.ArrayList;
import java.util.List;

/** Hand-driven {@link FeedRepository}: every call returns a subject the test completes. */
final class FakeFeedRepository implements FeedRepository {

    final List<SingleSubject<PostPage>> listPostsCalls = new ArrayList<>();
    final List<String> listPostsCursors = new ArrayList<>();
    final List<SingleSubject<Post>> getPostCalls = new ArrayList<>();
    final List<SingleSubject<Post>> createPostCalls = new ArrayList<>();
    final List<String> createPostBodies = new ArrayList<>();
    final List<String> createPostImageIds = new ArrayList<>();
    final List<String> createPostClientIds = new ArrayList<>();
    final List<CompletableSubject> deletePostCalls = new ArrayList<>();
    final List<SingleSubject<List<Reaction>>> setReactionCalls = new ArrayList<>();
    final List<String> setReactionEmoji = new ArrayList<>();
    final List<Boolean> setReactionActive = new ArrayList<>();
    final List<SingleSubject<CommentPage>> listCommentsCalls = new ArrayList<>();
    final List<String> listCommentsCursors = new ArrayList<>();
    final List<SingleSubject<Comment>> addCommentCalls = new ArrayList<>();
    final List<String> addCommentBodies = new ArrayList<>();
    final List<String> addCommentClientIds = new ArrayList<>();
    final List<CompletableSubject> deleteCommentCalls = new ArrayList<>();
    final List<SingleSubject<UploadedImage>> uploadImageCalls = new ArrayList<>();

    @Override public Single<PostPage> listPosts(int limit, @Nullable String cursor) {
        SingleSubject<PostPage> call = SingleSubject.create();
        listPostsCalls.add(call);
        listPostsCursors.add(cursor);
        return call;
    }

    @Override public Single<Post> getPost(@NonNull String postId) {
        SingleSubject<Post> call = SingleSubject.create();
        getPostCalls.add(call);
        return call;
    }

    @Override public Single<Post> createPost(
            @Nullable String body, @Nullable String imageId, @NonNull String clientPostId) {
        SingleSubject<Post> call = SingleSubject.create();
        createPostCalls.add(call);
        createPostBodies.add(body);
        createPostImageIds.add(imageId);
        createPostClientIds.add(clientPostId);
        return call;
    }

    @Override public Completable deletePost(@NonNull String postId) {
        CompletableSubject call = CompletableSubject.create();
        deletePostCalls.add(call);
        return call;
    }

    @Override public Single<List<Reaction>> setReaction(
            @NonNull String postId, @NonNull String emoji, boolean active) {
        SingleSubject<List<Reaction>> call = SingleSubject.create();
        setReactionCalls.add(call);
        setReactionEmoji.add(emoji);
        setReactionActive.add(active);
        return call;
    }

    @Override public Single<CommentPage> listComments(
            @NonNull String postId, int limit, @Nullable String cursor) {
        SingleSubject<CommentPage> call = SingleSubject.create();
        listCommentsCalls.add(call);
        listCommentsCursors.add(cursor);
        return call;
    }

    @Override public Single<Comment> addComment(
            @NonNull String postId, @NonNull String body, @NonNull String clientCommentId) {
        SingleSubject<Comment> call = SingleSubject.create();
        addCommentCalls.add(call);
        addCommentBodies.add(body);
        addCommentClientIds.add(clientCommentId);
        return call;
    }

    @Override public Completable deleteComment(@NonNull String commentId) {
        CompletableSubject call = CompletableSubject.create();
        deleteCommentCalls.add(call);
        return call;
    }

    @Override public Single<UploadedImage> uploadImage(@NonNull Uri uri) {
        SingleSubject<UploadedImage> call = SingleSubject.create();
        uploadImageCalls.add(call);
        return call;
    }
}

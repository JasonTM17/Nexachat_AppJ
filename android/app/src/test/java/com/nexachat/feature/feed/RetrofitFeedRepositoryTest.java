package com.nexachat.feature.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import com.nexachat.core.media.ImageCompressor;
import com.nexachat.data.SessionOperationTracker;
import com.squareup.moshi.Moshi;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

public final class RetrofitFeedRepositoryTest {

    private static final String POST_JSON = "{\"id\":\"post-1\",\"author\":{\"id\":\"author-1\","
            + "\"displayName\":\"An\",\"avatarUrl\":\"https://cdn/a.jpg\"},\"body\":\"hello\","
            + "\"image\":{\"url\":\"https://cdn/p.jpg\",\"width\":800,\"height\":600,"
            + "\"mimeType\":\"image/jpeg\"},\"createdAt\":\"2026-02-25T05:33:20Z\","
            + "\"commentCount\":3,\"reactions\":[{\"emoji\":\"\\uD83D\\uDC4D\",\"count\":4,"
            + "\"mine\":true},{\"emoji\":\"\\u2764\\uFE0F\",\"count\":0,\"mine\":false}],"
            + "\"mine\":true}";

    private FeedApi api;
    private ContentResolver resolver;
    private ImageCompressor compressor;
    private RetrofitFeedRepository repository;

    @Before public void setUp() {
        api = mock(FeedApi.class);
        Context context = mock(Context.class);
        resolver = mock(ContentResolver.class);
        compressor = mock(ImageCompressor.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getContentResolver()).thenReturn(resolver);
        repository = new RetrofitFeedRepository(
                api, context, compressor, new SessionOperationTracker());
    }

    @Test public void listPostsMapsRowsAndKeepsTheCursor() {
        FeedPostPageDto page = new FeedPostPageDto(
                List.of(postDto("post-1"), postDto(null), postDto("post-2")), "cursor-1");
        Call<FeedPostPageDto> call = success(page);
        when(api.listPosts(20, null)).thenReturn(call);

        FeedRepository.PostPage result = repository.listPosts(20, null).blockingGet();

        assertEquals(List.of("post-1", "post-2"),
                result.items().stream().map(FeedRepository.Post::id).toList());
        assertEquals("cursor-1", result.nextCursor());
    }

    @Test public void getPostMapsAuthorImageAndNonZeroReactions() {
        Call<FeedPostDto> call = success(parsePost(POST_JSON));
        when(api.getPost("post-1")).thenReturn(call);

        FeedRepository.Post post = repository.getPost("post-1").blockingGet();

        assertEquals("post-1", post.id());
        assertEquals("An", post.author().displayName());
        assertEquals("https://cdn/a.jpg", post.author().avatarUrl());
        assertEquals("hello", post.body());
        assertEquals("https://cdn/p.jpg", post.image().url());
        assertEquals(800, post.image().width());
        assertEquals(3, post.commentCount());
        assertTrue(post.mine());
        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 4, true)),
                post.reactions());
    }

    @Test public void aPostWithoutAnImageMapsToNull() {
        Call<FeedPostDto> call = success(parsePost("{\"id\":\"post-1\",\"body\":\"hello\"}"));
        when(api.getPost("post-1")).thenReturn(call);

        FeedRepository.Post post = repository.getPost("post-1").blockingGet();

        assertNull(post.image());
        assertNull(post.author().id());
        assertTrue(post.reactions().isEmpty());
    }

    @Test public void createPostSendsTheDraftUnchanged() {
        Call<FeedPostDto> call = success(postDto("post-1"));
        when(api.createPost(any())).thenReturn(call);

        FeedRepository.Post post = repository
                .createPost("hello", "image-1", "client-1").blockingGet();

        assertEquals("post-1", post.id());
        ArgumentCaptor<FeedCreatePostRequest> sent =
                ArgumentCaptor.forClass(FeedCreatePostRequest.class);
        verify(api).createPost(sent.capture());
        String wire = json(sent.getValue());
        assertTrue(wire, wire.contains("\"body\":\"hello\""));
        assertTrue(wire, wire.contains("\"imageId\":\"image-1\""));
        assertTrue(wire, wire.contains("\"clientPostId\":\"client-1\""));
    }

    @Test public void setReactionPicksAddOrRemove() {
        FeedReactionsDto tally = new FeedReactionsDto(
                List.of(new FeedReactionDto("\uD83D\uDC4D", 5, true)));
        Call<FeedReactionsDto> addCall = success(tally);
        Call<FeedReactionsDto> removeCall = success(tally);
        when(api.addReaction("post-1", "\uD83D\uDC4D")).thenReturn(addCall);
        when(api.removeReaction("post-1", "\uD83D\uDC4D")).thenReturn(removeCall);

        repository.setReaction("post-1", "\uD83D\uDC4D", true).blockingGet();
        repository.setReaction("post-1", "\uD83D\uDC4D", false).blockingGet();

        verify(api).addReaction("post-1", "\uD83D\uDC4D");
        verify(api).removeReaction("post-1", "\uD83D\uDC4D");
    }

    @Test public void listCommentsFallsBackToTheRequestedPostId() {
        FeedCommentPageDto page = new FeedCommentPageDto(
                List.of(new FeedCommentDto("comment-1", null, null, "hi", null, false)), null);
        Call<FeedCommentPageDto> call = success(page);
        when(api.listComments("post-1", 30, null)).thenReturn(call);

        FeedRepository.CommentPage result =
                repository.listComments("post-1", 30, null).blockingGet();

        assertEquals("post-1", result.items().get(0).postId());
        assertNull(result.nextCursor());
    }

    @Test public void addCommentMapsTheCreatedRow() {
        FeedCommentDto dto = new FeedCommentDto(
                "comment-1", "post-1", null, "hi", "2026-02-25T05:33:20Z", true);
        Call<FeedCommentDto> call = success(dto);
        when(api.createComment(eq("post-1"), any())).thenReturn(call);

        FeedRepository.Comment comment = repository
                .addComment("post-1", "hi", "client-1").blockingGet();

        assertEquals("comment-1", comment.id());
        assertEquals("post-1", comment.postId());
        assertTrue(comment.mine());
        ArgumentCaptor<FeedCreateCommentRequest> sent =
                ArgumentCaptor.forClass(FeedCreateCommentRequest.class);
        verify(api).createComment(eq("post-1"), sent.capture());
        String wire = json(sent.getValue());
        assertTrue(wire, wire.contains("\"body\":\"hi\""));
        assertTrue(wire, wire.contains("\"clientCommentId\":\"client-1\""));
    }

    @Test public void deletePostCompletesOnAnEmptySuccess() {
        Call<ResponseBody> call = emptySuccess();
        when(api.deletePost("post-1")).thenReturn(call);

        repository.deletePost("post-1").blockingAwait();

        verify(api).deletePost("post-1");
    }

    @Test public void anHttpErrorCarriesTheStatusAndServerCode() {
        Call<FeedPostDto> call = failure(404, "POST_NOT_FOUND");
        when(api.getPost("post-1")).thenReturn(call);

        try {
            repository.getPost("post-1").blockingGet();
            fail("expected a FeedApiException");
        } catch (FeedApiException expected) {
            assertEquals(404, expected.getStatus());
            assertEquals("POST_NOT_FOUND", expected.getCode());
            assertTrue(expected.isGone());
        }
    }

    @Test public void anEmptySuccessBodyIsAnError() {
        Call<FeedPostDto> call = success(null);
        when(api.getPost("post-1")).thenReturn(call);

        try {
            repository.getPost("post-1").blockingGet();
            fail("expected a FeedApiException");
        } catch (FeedApiException expected) {
            assertEquals("EMPTY_RESPONSE", expected.getCode());
        }
    }

    @Test public void uploadImageAbortsWhenTheCompressorFails() throws Exception {
        Uri uri = mock(Uri.class);
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(resolver.getType(uri)).thenReturn("image/jpeg");
        when(compressor.compress(any(), anyString(), isNull()))
                .thenThrow(new IOException("cannot strip metadata"));

        assertTrue(uploadFailure(uri).getMessage().contains("public post"));
        verify(api, never()).uploadImage(any());
    }

    @Test public void uploadImageAbortsWhenTheCompressorReturnsNothing() throws Exception {
        Uri uri = mock(Uri.class);
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(resolver.getType(uri)).thenReturn("image/jpeg");
        when(compressor.compress(any(), anyString(), isNull())).thenReturn(null);

        assertTrue(uploadFailure(uri).getMessage().contains("unsupported image type"));
        verify(api, never()).uploadImage(any());
    }

    @Test public void uploadImageRefusesATypeTheServerDoesNotAccept() throws Exception {
        Uri uri = mock(Uri.class);
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(resolver.getType(uri)).thenReturn("image/gif");
        ImageCompressor.Result result = mock(ImageCompressor.Result.class);
        when(result.mimeType()).thenReturn("image/gif");
        when(compressor.compress(any(), eq("image/gif"), isNull())).thenReturn(result);

        assertTrue(uploadFailure(uri).getMessage().contains("unsupported image type"));
        verify(api, never()).uploadImage(any());
    }

    @Test public void uploadImageRefusesAnUnreadablePick() throws Exception {
        Uri uri = mock(Uri.class);
        when(resolver.openInputStream(uri)).thenReturn(null);

        assertTrue(uploadFailure(uri).getMessage().contains("unreadable"));
        verify(api, never()).uploadImage(any());
    }

    @Test public void uploadImagePostsTheCompressedBytes() throws Exception {
        Uri uri = mock(Uri.class);
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(resolver.getType(uri)).thenReturn("image/jpeg");
        ImageCompressor.Result result = mock(ImageCompressor.Result.class);
        when(result.mimeType()).thenReturn("image/jpeg");
        when(result.bytes()).thenReturn(new byte[]{9, 9});
        when(result.file()).thenReturn(null);
        when(result.fileName()).thenReturn("photo.jpg");
        when(compressor.compress(any(), eq("image/jpeg"), isNull())).thenReturn(result);
        Call<FeedImageUploadDto> call = success(
                new FeedImageUploadDto("image-1", "https://cdn/1.jpg", 10, 20, "image/jpeg"));
        when(api.uploadImage(any())).thenReturn(call);

        FeedRepository.UploadedImage uploaded = repository.uploadImage(uri).blockingGet();

        assertEquals("image-1", uploaded.imageId());
        assertEquals("https://cdn/1.jpg", uploaded.url());
        assertEquals(10, uploaded.width());
        assertEquals(20, uploaded.height());
        assertEquals("image/jpeg", uploaded.mimeType());
    }

    @Test public void uploadImageFailsWhenTheServerReturnsNoId() throws Exception {
        Uri uri = mock(Uri.class);
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(resolver.getType(uri)).thenReturn("image/jpeg");
        ImageCompressor.Result result = mock(ImageCompressor.Result.class);
        when(result.mimeType()).thenReturn("image/jpeg");
        when(result.bytes()).thenReturn(new byte[]{9});
        when(result.file()).thenReturn(null);
        when(result.fileName()).thenReturn("photo.jpg");
        when(compressor.compress(any(), eq("image/jpeg"), isNull())).thenReturn(result);
        Call<FeedImageUploadDto> call = success(
                new FeedImageUploadDto(null, null, 0, 0, null));
        when(api.uploadImage(any())).thenReturn(call);

        try {
            repository.uploadImage(uri).blockingGet();
            fail("expected a FeedApiException");
        } catch (FeedApiException expected) {
            assertEquals("EMPTY_RESPONSE", expected.getCode());
        }
    }

    /** The IOException the upload aborted with, unwrapped from the RxJava runtime wrapper. */
    private IOException uploadFailure(Uri uri) {
        try {
            repository.uploadImage(uri).blockingGet();
            throw new AssertionError("expected the upload to abort");
        } catch (RuntimeException failure) {
            if (failure.getCause() instanceof IOException io) {
                return io;
            }
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static String json(Object value) {
        return ((com.squareup.moshi.JsonAdapter<Object>) new Moshi.Builder().build()
                .adapter(value.getClass())).toJson(value);
    }

    private static FeedPostDto postDto(String id) {
        return new FeedPostDto(id, null, "body", null, null, 0, List.of(), false);
    }

    private static FeedPostDto parsePost(String json) {
        try {
            return new Moshi.Builder().build().adapter(FeedPostDto.class).fromJson(json);
        } catch (IOException malformed) {
            throw new AssertionError(malformed);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> success(T body) {
        Call<T> call = mock(Call.class);
        try {
            when(call.execute()).thenReturn(Response.success(body));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return call;
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> emptySuccess() {
        Call<T> call = mock(Call.class);
        try {
            when(call.execute()).thenReturn(Response.success(204, null));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return call;
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> failure(int status, String code) {
        Call<T> call = mock(Call.class);
        ResponseBody body = ResponseBody.create(
                "{\"error\":{\"code\":\"" + code + "\"}}",
                MediaType.parse("application/json"));
        try {
            when(call.execute()).thenReturn(Response.error(status, body));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return call;
    }
}

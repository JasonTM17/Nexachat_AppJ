package com.nexachat.feature.feed;

import androidx.annotation.Nullable;
import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Authenticated Retrofit contract for the public feed. Every call goes through the auth
 * interceptor; the server decides visibility (blocked authors are filtered server-side).
 */
public interface FeedApi {

    @GET("api/v1/feed/posts")
    Call<FeedPostPageDto> listPosts(
            @Query("limit") int limit,
            @Query("cursor") @Nullable String cursor);

    @POST("api/v1/feed/posts")
    Call<FeedPostDto> createPost(@Body FeedCreatePostRequest body);

    @GET("api/v1/feed/posts/{id}")
    Call<FeedPostDto> getPost(@Path("id") String postId);

    @DELETE("api/v1/feed/posts/{id}")
    Call<ResponseBody> deletePost(@Path("id") String postId);

    @PUT("api/v1/feed/posts/{id}/reactions/{emoji}")
    Call<FeedReactionsDto> addReaction(
            @Path("id") String postId, @Path("emoji") String emoji);

    @DELETE("api/v1/feed/posts/{id}/reactions/{emoji}")
    Call<FeedReactionsDto> removeReaction(
            @Path("id") String postId, @Path("emoji") String emoji);

    @GET("api/v1/feed/posts/{id}/comments")
    Call<FeedCommentPageDto> listComments(
            @Path("id") String postId,
            @Query("limit") int limit,
            @Query("cursor") @Nullable String cursor);

    @POST("api/v1/feed/posts/{id}/comments")
    Call<FeedCommentDto> createComment(
            @Path("id") String postId, @Body FeedCreateCommentRequest body);

    @DELETE("api/v1/feed/comments/{id}")
    Call<ResponseBody> deleteComment(@Path("id") String commentId);

    @Multipart
    @POST("api/v1/feed/images")
    Call<FeedImageUploadDto> uploadImage(@Part MultipartBody.Part file);
}

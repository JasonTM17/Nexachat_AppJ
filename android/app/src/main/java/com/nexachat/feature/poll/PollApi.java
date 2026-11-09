package com.nexachat.feature.poll;

import androidx.annotation.Nullable;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Authenticated Retrofit contract for group polls and their change feed. */
public interface PollApi {

    @GET("api/v1/conversations/{id}/polls")
    Call<PollPageDto> list(
            @Path("id") String conversationId,
            @Query("limit") int limit,
            @Nullable @Query("before") String before);

    @GET("api/v1/conversations/{id}/polls/changes")
    Call<PollChangesDto> changes(
            @Path("id") String conversationId,
            @Query("since") long since,
            @Query("limit") int limit);

    @POST("api/v1/conversations/{id}/polls")
    Call<PollDto> create(
            @Path("id") String conversationId,
            @Body CreatePollRequest request);

    @PUT("api/v1/conversations/{id}/polls/{pollId}/vote")
    Call<PollDto> vote(
            @Path("id") String conversationId,
            @Path("pollId") String pollId,
            @Body VoteRequest request);

    @DELETE("api/v1/conversations/{id}/polls/{pollId}/vote")
    Call<PollDto> retractVote(
            @Path("id") String conversationId,
            @Path("pollId") String pollId);

    @POST("api/v1/conversations/{id}/polls/{pollId}/close")
    Call<PollDto> close(
            @Path("id") String conversationId,
            @Path("pollId") String pollId);

    @DELETE("api/v1/conversations/{id}/polls/{pollId}")
    Call<Void> delete(
            @Path("id") String conversationId,
            @Path("pollId") String pollId);
}

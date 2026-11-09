package com.nexachat.feature.invite;

import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Authenticated API for invite links and join requests. */
public interface InviteApi {

    @POST("api/v1/conversations/{conversationId}/invite-link")
    Call<InviteLinkResponse> createOrRotateLink(
            @Path("conversationId") String conversationId,
            @Body InviteLinkRequest request);

    @GET("api/v1/conversations/{conversationId}/invite-link")
    Call<InviteLinkResponse> getActiveLink(@Path("conversationId") String conversationId);

    @PATCH("api/v1/conversations/{conversationId}/invite-link")
    Call<InviteLinkResponse> updateApproval(
            @Path("conversationId") String conversationId,
            @Body InviteApprovalRequest request);

    @DELETE("api/v1/conversations/{conversationId}/invite-link")
    Call<Void> revokeLink(@Path("conversationId") String conversationId);

    @GET("api/v1/conversations/{conversationId}/join-requests")
    Call<List<JoinRequestResponse>> listJoinRequests(
            @Path("conversationId") String conversationId,
            @Query("status") String status);

    @GET("api/v1/conversations/{conversationId}/join-requests/me")
    Call<JoinRequestResponse> getMyJoinRequest(
            @Path("conversationId") String conversationId);

    @POST("api/v1/conversations/{conversationId}/join-requests/{requestId}/approve")
    Call<JoinRequestResponse> approveJoinRequest(
            @Path("conversationId") String conversationId,
            @Path("requestId") String requestId);

    @POST("api/v1/conversations/{conversationId}/join-requests/{requestId}/reject")
    Call<JoinRequestResponse> rejectJoinRequest(
            @Path("conversationId") String conversationId,
            @Path("requestId") String requestId);

    @GET("api/v1/invite-links/{token}")
    @Headers("Cache-Control: no-store")
    Call<InvitePreviewResponse> loadPreview(@Path("token") String token);

    @POST("api/v1/invite-links/{token}/join")
    @Headers("Cache-Control: no-store")
    Call<JoinResponse> join(@Path("token") String token);
}

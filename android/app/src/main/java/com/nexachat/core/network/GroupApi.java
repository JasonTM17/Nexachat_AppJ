package com.nexachat.core.network;

import com.nexachat.core.network.dto.AddGroupMembersRequest;
import com.nexachat.core.network.dto.AvatarResponse;
import com.nexachat.core.network.dto.ConversationDto;
import com.nexachat.core.network.dto.CreateGroupRequest;
import com.nexachat.core.network.dto.UpdateConversationRequest;
import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Multipart;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;

/** Group-management endpoints under {@code /api/v1/conversations}. */
public interface GroupApi {

    @POST("api/v1/conversations/group")
    Call<ConversationDto> createGroup(@Body CreateGroupRequest body);

    @POST("api/v1/conversations/{id}/members")
    Call<ResponseBody> addMembers(
            @Path("id") String conversationId,
            @Body AddGroupMembersRequest body
    );

    @DELETE("api/v1/conversations/{id}/members/{userId}")
    Call<ResponseBody> removeMember(
            @Path("id") String conversationId,
            @Path("userId") String userId
    );

    @POST("api/v1/conversations/{id}/leave")
    Call<ResponseBody> leaveGroup(@Path("id") String conversationId);

    @PATCH("api/v1/conversations/{id}")
    Call<ResponseBody> updateConversation(
            @Path("id") String conversationId,
            @Body UpdateConversationRequest body
    );

    @Multipart
    @POST("api/v1/conversations/{id}/avatar")
    Call<AvatarResponse> uploadAvatar(
            @Path("id") String conversationId,
            @Part MultipartBody.Part file
    );
}

package com.nexachat.core.network

import com.nexachat.core.network.dto.AddGroupMembersRequest
import com.nexachat.core.network.dto.AvatarResponse
import com.nexachat.core.network.dto.ConversationDto
import com.nexachat.core.network.dto.CreateGroupRequest
import com.nexachat.core.network.dto.UpdateConversationRequest
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * `/api/v1/conversations…` group-management endpoints (phase-08, plan §10.2).
 * Same `Response<T>` convention as the other APIs so callers can read the
 * status code and the standard `{error:{code,message}}` envelope.
 *
 * Mutation endpoints return [ResponseBody] rather than `Unit`: the parallel
 * server build may answer 204 (empty) or 200 (updated resource), and a typed
 * `Unit` body would turn a bodied 200 into a ClassCastException inside
 * `safeApiCall`. [createGroup] stays typed — the contract fixes it at
 * `201 ConversationDto`.
 */
interface GroupApi {

    /** Creates a GROUP conversation; creator becomes ADMIN server-side. */
    @POST("api/v1/conversations/group")
    suspend fun createGroup(@Body body: CreateGroupRequest): Response<ConversationDto>

    /** Adds members (ADMIN-only server-side). */
    @POST("api/v1/conversations/{id}/members")
    suspend fun addMembers(
        @Path("id") conversationId: String,
        @Body body: AddGroupMembersRequest,
    ): Response<ResponseBody>

    /** Removes one member (ADMIN-only server-side). */
    @DELETE("api/v1/conversations/{id}/members/{userId}")
    suspend fun removeMember(
        @Path("id") conversationId: String,
        @Path("userId") userId: String,
    ): Response<ResponseBody>

    /** Self-leave — any member may call it. */
    @POST("api/v1/conversations/{id}/leave")
    suspend fun leaveGroup(@Path("id") conversationId: String): Response<ResponseBody>

    /** Renames / re-avatars the group (ADMIN-only server-side). */
    @PATCH("api/v1/conversations/{id}")
    suspend fun updateConversation(
        @Path("id") conversationId: String,
        @Body body: UpdateConversationRequest,
    ): Response<ResponseBody>

    /**
     * Group avatar upload (ADMIN-only server-side, FR-D4): image is sniffed
     * + stored under `avatars/` and `CONVERSATION_UPDATED` carries the new
     * `avatarUrl` to every member.
     */
    @Multipart
    @POST("api/v1/conversations/{id}/avatar")
    suspend fun uploadAvatar(
        @Path("id") conversationId: String,
        @Part file: MultipartBody.Part,
    ): Response<AvatarResponse>
}

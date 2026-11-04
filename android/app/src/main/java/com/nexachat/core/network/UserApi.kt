package com.nexachat.core.network

import com.nexachat.core.network.dto.AddContactRequest
import com.nexachat.core.network.dto.AvatarResponse
import com.nexachat.core.network.dto.ContactDto
import com.nexachat.core.network.dto.ContactEnvelopeDto
import com.nexachat.core.network.dto.DirectConversationRequest
import com.nexachat.core.network.dto.DirectConversationResponse
import com.nexachat.core.network.dto.UpdateContactRequest
import com.nexachat.core.network.dto.UpdateProfileRequest
import com.nexachat.core.network.dto.UserDto
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * '/api/v1/users', '/api/v1/contacts' and '/api/v1/conversations/direct'
 * endpoints (plan §10.2). `Response<T>` (not `Call`/`T`) so callers can read
 * the status code and parse the error envelope themselves — same convention
 * as [AuthApi]. All routes require the Bearer token added by AuthInterceptor.
 */
interface UserApi {

    /**
     * User discovery (rate-limited 30/min per user on the server). Exactly one of
     * [phone]/[q] is sent — Retrofit drops null query params. Phone searches
     * are exact E.164 matches; `q` is a display-name prefix (≤20 results).
     */
    @GET("api/v1/users/search")
    suspend fun searchUsers(
        @Query("phone") phone: String? = null,
        @Query("q") q: String? = null,
    ): Response<List<UserDto>>

    @GET("api/v1/users/me")
    suspend fun getMe(): Response<UserDto>

    @PATCH("api/v1/users/me")
    suspend fun updateMe(@Body body: UpdateProfileRequest): Response<UserDto>

    /**
     * Self-service avatar upload (`POST /users/me/avatar`, multipart `file`).
     * Server sniffs image bytes, persists `avatar_url` on the user row and
     * returns the served path — fetch [getMe] again for the fresh record.
     */
    @Multipart
    @POST("api/v1/users/me/avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): Response<AvatarResponse>

    @GET("api/v1/contacts")
    suspend fun getContacts(): Response<List<ContactDto>>

    @POST("api/v1/contacts")
    suspend fun addContact(@Body body: AddContactRequest): Response<ContactEnvelopeDto>

    @PATCH("api/v1/contacts/{userId}")
    suspend fun updateContact(
        @Path("userId") userId: String,
        @Body body: UpdateContactRequest,
    ): Response<ContactEnvelopeDto>

    @DELETE("api/v1/contacts/{userId}")
    suspend fun removeContact(@Path("userId") userId: String): Response<Unit>

    /**
     * Opens (or returns the existing) direct conversation — idempotent via the
     * server's `direct_key` unique constraint (plan §10.2, P5).
     */
    @POST("api/v1/conversations/direct")
    suspend fun openDirectConversation(
        @Body body: DirectConversationRequest,
    ): Response<DirectConversationResponse>
}

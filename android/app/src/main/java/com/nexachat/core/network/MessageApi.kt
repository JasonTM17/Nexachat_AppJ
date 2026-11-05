package com.nexachat.core.network

import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.dto.MessagePageDto
import com.nexachat.core.network.dto.EditMessageRequest
import com.nexachat.core.network.dto.PinRequest
import com.nexachat.core.network.dto.ReactionRequest
import com.nexachat.core.network.dto.ReactionUpdateDto
import com.nexachat.core.network.dto.ReadRequest
import com.nexachat.core.network.dto.SendMessageRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `/api/v1/conversations…` message endpoints (plan §10.2). Same `Response<T>`
 * convention as [AuthApi]/[UserApi] so callers see status + error envelope.
 *
 * [getConversations] deliberately returns the raw [ResponseBody]: the server
 * returns a bare array and, for paged requests, places the opaque next cursor
 * in the `X-Next-Cursor` response header. The repository reads that header
 * alongside the body.
 */
interface MessageApi {

    @GET("api/v1/conversations")
    suspend fun getConversations(
        @Query("limit") limit: Int? = null,
        @Query("cursor") cursor: String? = null,
    ): Response<ResponseBody>

    /**
     * Cursor-paginated history: newest-first pages via `beforeSeq` (plan §10.1).
     * `afterSeq` (mutually exclusive server-side) pages oldest-first — the
     * reconnect forward-resync of plan §11.3.5.
     */
    @GET("api/v1/conversations/{id}/messages")
    suspend fun getMessages(
        @Path("id") conversationId: String,
        @Query("beforeSeq") beforeSeq: Long? = null,
        @Query("afterSeq") afterSeq: Long? = null,
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
    ): Response<MessagePageDto>

    /** REST send path — the fallback when the WS send could not be queued. */
    @POST("api/v1/conversations/{id}/messages")
    suspend fun sendMessage(
        @Path("id") conversationId: String,
        @Body body: SendMessageRequest,
    ): Response<MessageDto>

    /** Read watermark update (plan §10.2 `{upToSeq}` → 204). */
    @POST("api/v1/conversations/{id}/read")
    suspend fun markRead(
        @Path("id") conversationId: String,
        @Body body: ReadRequest,
    ): Response<Unit>

    /**
     * Edit (F1): author only, TEXT only, non-deleted. The response is the
     * hydrated message with `editedAt` set.
     */
    @PATCH("api/v1/conversations/{id}/messages/{messageId}")
    suspend fun editMessage(
        @Path("id") conversationId: String,
        @Path("messageId") messageId: String,
        @Body body: EditMessageRequest,
    ): Response<MessageDto>

    /** Soft delete for everyone (F2) — 204, idempotent, author only. */
    @DELETE("api/v1/conversations/{id}/messages/{messageId}")
    suspend fun deleteMessage(
        @Path("id") conversationId: String,
        @Path("messageId") messageId: String,
    ): Response<Unit>

    /** Search inside one conversation (F4) — newest-first, member only. */
    @GET("api/v1/conversations/{id}/messages/search")
    suspend fun searchMessages(
        @Path("id") conversationId: String,
        @Query("q") query: String,
        @Query("limit") limit: Int = DEFAULT_SEARCH_LIMIT,
        @Query("beforeSeq") beforeSeq: Long? = null,
    ): Response<MessagePageDto>

    // ---- phase-10 interactions ----

    /**
     * Toggles one emoji reaction on a message; the response is the updated
     * `{messageId, reactions[]}` summary — the same shape the
     * `REACTION_UPDATED` broadcast carries.
     */
    @POST("api/v1/messages/{id}/reactions")
    suspend fun toggleReaction(
        @Path("id") messageId: String,
        @Body body: ReactionRequest,
    ): Response<ReactionUpdateDto>

    /**
     * Pinned-message ids for one conversation. Raw body like
     * [getConversations]: the contract allows a bare id list, a list of pin
     * objects, or an `{items:…}` envelope — the repository parses tolerantly.
     */
    @GET("api/v1/conversations/{id}/pins")
    suspend fun getPins(
        @Path("id") conversationId: String,
    ): Response<ResponseBody>

    /** Pins a message (DIRECT: member; GROUP: ADMIN — enforced server-side). */
    @POST("api/v1/conversations/{id}/pins")
    suspend fun pinMessage(
        @Path("id") conversationId: String,
        @Body body: PinRequest,
    ): Response<ResponseBody>

    @DELETE("api/v1/conversations/{id}/pins/{messageId}")
    suspend fun unpinMessage(
        @Path("id") conversationId: String,
        @Path("messageId") messageId: String,
    ): Response<ResponseBody>

    companion object {
        const val DEFAULT_PAGE_SIZE = 30

        /** Server cap for F4 search is 50; the default page is 20. */
        const val DEFAULT_SEARCH_LIMIT = 20
    }
}

package com.nexachat.core.network

import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.dto.MessagePageDto
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
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * `/api/v1/conversations…` message endpoints (plan §10.2). Same `Response<T>`
 * convention as [AuthApi]/[UserApi] so callers see status + error envelope.
 *
 * [getConversations] deliberately returns the raw [ResponseBody]: the frozen
 * contract lists `beforeSeq`/`limit` params (suggesting a
 * `{items,nextBeforeSeq,hasMore}` envelope) but a bare JSON array is an
 * equally plausible server build — the repository decides after one lookahead
 * character instead of gambling on one shape.
 */
interface MessageApi {

    @GET("api/v1/conversations")
    suspend fun getConversations(
        @Query("beforeSeq") beforeSeq: Long? = null,
        @Query("limit") limit: Int? = null,
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
    }
}

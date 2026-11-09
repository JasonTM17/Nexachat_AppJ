package com.nexachat.core.network;

import com.nexachat.core.network.dto.EditMessageRequest;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessagePageDto;
import com.nexachat.core.network.dto.PinRequest;
import com.nexachat.core.network.dto.ReadRequest;
import com.nexachat.core.network.dto.ReactionRequest;
import com.nexachat.core.network.dto.ReactionUpdateDto;
import com.nexachat.core.network.dto.SendMessageRequest;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Message endpoints under {@code /api/v1/conversations}. */
public interface MessageApi {

    public static final int DEFAULT_PAGE_SIZE = 30;
    public static final int DEFAULT_SEARCH_LIMIT = 20;

    @GET("api/v1/conversations")
    Call<ResponseBody> getConversations(
            @Query("limit") Integer limit,
            @Query("cursor") String cursor
    );

    /**
     * Archived inbox variant. The server hides archived rows unless
     * {@code archived=true} is present, so this is a distinct endpoint shape
     * instead of an optional query on the default call.
     */
    @GET("api/v1/conversations?archived=true")
    Call<ResponseBody> getArchivedConversations(
            @Query("limit") Integer limit,
            @Query("cursor") String cursor
    );

    @GET("api/v1/conversations/{id}/messages")
    Call<MessagePageDto> getMessages(
            @Path("id") String conversationId,
            @Query("beforeSeq") Long beforeSeq,
            @Query("afterSeq") Long afterSeq,
            @Query("limit") int limit
    );

    @POST("api/v1/conversations/{id}/messages")
    Call<MessageDto> sendMessage(
            @Path("id") String conversationId,
            @Body SendMessageRequest body
    );

    @POST("api/v1/conversations/{id}/read")
    Call<Void> markRead(
            @Path("id") String conversationId,
            @Body ReadRequest body
    );

    @PATCH("api/v1/conversations/{id}/messages/{messageId}")
    Call<MessageDto> editMessage(
            @Path("id") String conversationId,
            @Path("messageId") String messageId,
            @Body EditMessageRequest body
    );

    @DELETE("api/v1/conversations/{id}/messages/{messageId}")
    Call<Void> deleteMessage(
            @Path("id") String conversationId,
            @Path("messageId") String messageId
    );

    @GET("api/v1/conversations/{id}/messages/search")
    Call<MessagePageDto> searchMessages(
            @Path("id") String conversationId,
            @Query("q") String query,
            @Query("limit") int limit,
            @Query("beforeSeq") Long beforeSeq
    );

    @POST("api/v1/messages/{id}/reactions")
    Call<ReactionUpdateDto> toggleReaction(
            @Path("id") String messageId,
            @Body ReactionRequest body,
            @Header("Idempotency-Key") String idempotencyKey
    );

    @PUT("api/v1/messages/{id}/reactions/{emoji}")
    Call<ReactionUpdateDto> addReaction(
            @Path("id") String messageId,
            @Path("emoji") String emoji,
            @Header("Idempotency-Key") String idempotencyKey
    );

    @DELETE("api/v1/messages/{id}/reactions/{emoji}")
    Call<ReactionUpdateDto> removeReaction(
            @Path("id") String messageId,
            @Path("emoji") String emoji,
            @Header("Idempotency-Key") String idempotencyKey
    );

    @GET("api/v1/conversations/{id}/pins")
    Call<ResponseBody> getPins(@Path("id") String conversationId);

    @POST("api/v1/conversations/{id}/pins")
    Call<ResponseBody> pinMessage(
            @Path("id") String conversationId,
            @Body PinRequest body,
            @Header("Idempotency-Key") String idempotencyKey
    );

    @DELETE("api/v1/conversations/{id}/pins/{messageId}")
    Call<ResponseBody> unpinMessage(
            @Path("id") String conversationId,
            @Path("messageId") String messageId,
            @Header("Idempotency-Key") String idempotencyKey
    );

    @POST("api/v1/sync")
    Call<SyncResponse> syncDeltas(@Body SyncRequest request);
}

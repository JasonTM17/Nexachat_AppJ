package com.nexachat.core.network;

import com.nexachat.core.network.dto.ConversationAiOptOutDto;
import com.nexachat.core.network.dto.MuteRequest;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.PUT;
import retrofit2.http.Path;

/**
 * Per-member conversation preferences stored by the server: mute, archive and
 * the caller's own AI opt-out. All operations are idempotent.
 */
public interface ConversationSettingsApi {

    @PUT("api/v1/conversations/{id}/mute")
    Call<Void> mute(@Path("id") String conversationId, @Body MuteRequest body);

    @DELETE("api/v1/conversations/{id}/mute")
    Call<Void> unmute(@Path("id") String conversationId);

    @PUT("api/v1/conversations/{id}/archive")
    Call<Void> archive(@Path("id") String conversationId);

    @DELETE("api/v1/conversations/{id}/archive")
    Call<Void> unarchive(@Path("id") String conversationId);

    @GET("api/v1/conversations/{id}/ai-opt-out")
    Call<ConversationAiOptOutDto> getAiOptOut(@Path("id") String conversationId);

    @PUT("api/v1/conversations/{id}/ai-opt-out")
    Call<ConversationAiOptOutDto> optOutOfAi(@Path("id") String conversationId);

    @DELETE("api/v1/conversations/{id}/ai-opt-out")
    Call<ConversationAiOptOutDto> clearAiOptOut(@Path("id") String conversationId);
}

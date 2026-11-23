package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/** Authenticated Retrofit contract for conversation scheduled messages. */
public interface ScheduledMessageApi {

    @GET("api/v1/conversations/{id}/scheduled-messages")
    Call<List<ScheduledMessageDto>> list(@Path("id") String conversationId);

    @POST("api/v1/conversations/{id}/scheduled-messages")
    Call<ScheduledMessageDto> create(
            @Path("id") String conversationId,
            @NonNull @Body ScheduledMessageRequest request);

    @DELETE("api/v1/conversations/{id}/scheduled-messages/{scheduledId}")
    Call<Void> delete(
            @Path("id") String conversationId,
            @Path("scheduledId") String scheduledId);
}

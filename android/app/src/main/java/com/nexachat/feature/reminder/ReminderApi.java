package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;

/** Authenticated Retrofit contract for conversation reminders. */
public interface ReminderApi {

    @GET("api/v1/conversations/{id}/reminders")
    Call<List<ReminderDto>> list(@Path("id") String conversationId);

    @POST("api/v1/conversations/{id}/reminders")
    Call<ReminderDto> create(
            @Path("id") String conversationId,
            @Header("Idempotency-Key") String idempotencyKey,
            @Body ReminderRequest request);

    @PATCH("api/v1/conversations/{id}/reminders/{reminderId}")
    Call<ReminderDto> update(
            @Path("id") String conversationId,
            @Path("reminderId") String reminderId,
            @Body ReminderRequest request);

    @DELETE("api/v1/conversations/{id}/reminders/{reminderId}")
    Call<Void> delete(
            @Path("id") String conversationId,
            @Path("reminderId") String reminderId);
}

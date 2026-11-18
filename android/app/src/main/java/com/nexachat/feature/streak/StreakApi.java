package com.nexachat.feature.streak;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/** Authenticated Retrofit contract for chat and check-in streaks. */
public interface StreakApi {

    @GET("api/v1/streaks/me")
    Call<StreakDto> getMine();

    /** Records that the app was opened today; idempotent. */
    @POST("api/v1/streaks/check-in")
    Call<StreakDto> checkIn();

    @GET("api/v1/conversations/{id}/streak")
    Call<StreakDto> getConversationStreak(@Path("id") String conversationId);
}

package com.nexachat.summary;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.PATCH;

/** Additive client contract for the account-level summary consent setting. */
public interface AiConsentApi {
    @GET("api/v1/users/me/settings")
    Call<AiSummaryConsentResponse> getSettings();

    @PATCH("api/v1/users/me/settings")
    Call<AiSummaryConsentResponse> updateSettings(@Body AiSummaryConsentRequest request);
}

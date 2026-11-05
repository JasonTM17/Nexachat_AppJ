package com.nexachat.summary;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Path;

/** Authenticated Retrofit API for on-demand conversation summaries. */
public interface SummaryApi {
    @POST("api/v1/conversations/{id}/summary")
    Call<SummaryResponse> summarize(
            @Path("id") String conversationId,
            @Body SummaryRequest request);
}

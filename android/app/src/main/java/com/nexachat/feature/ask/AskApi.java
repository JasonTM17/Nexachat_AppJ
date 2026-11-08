package com.nexachat.feature.ask;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Path;

/** Authenticated Retrofit API for questions about one conversation. */
public interface AskApi {
    @POST("api/v1/conversations/{id}/ask")
    Call<AskResponse> ask(
            @Path("id") String conversationId,
            @Body AskRequest request);
}

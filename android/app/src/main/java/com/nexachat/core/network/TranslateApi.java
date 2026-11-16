package com.nexachat.core.network;

import com.nexachat.core.network.dto.TranslateBatchRequest;
import com.nexachat.core.network.dto.TranslateBatchResponse;
import com.nexachat.core.network.dto.TranslatePrefsDto;
import com.nexachat.core.network.dto.TranslateRequest;
import com.nexachat.core.network.dto.TranslateResponseDto;
import com.nexachat.core.network.dto.TranslationGlossaryDto;
import com.nexachat.core.network.dto.UpdateTranslatePrefsRequest;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;

/** Message translation endpoints under {@code /api/v1}. */
public interface TranslateApi {

    @POST("api/v1/messages/{id}/translate")
    Call<TranslateResponseDto> translate(
            @Path("id") String messageId,
            @Body TranslateRequest body
    );

    @POST("api/v1/conversations/{id}/translate-batch")
    Call<TranslateBatchResponse> translateBatch(
            @Path("id") String conversationId,
            @Body TranslateBatchRequest body
    );

    @GET("api/v1/conversations/{id}/translation-glossary")
    Call<TranslationGlossaryDto> getGlossary(@Path("id") String conversationId);

    @PUT("api/v1/conversations/{id}/translation-glossary")
    Call<TranslationGlossaryDto> putGlossary(
            @Path("id") String conversationId,
            @Body TranslationGlossaryDto body
    );

    @GET("api/v1/conversations/{id}/translate-prefs")
    Call<TranslatePrefsDto> getPrefs(@Path("id") String conversationId);

    @PUT("api/v1/conversations/{id}/translate-prefs")
    Call<TranslatePrefsDto> updatePrefs(
            @Path("id") String conversationId,
            @Body UpdateTranslatePrefsRequest body
    );
}

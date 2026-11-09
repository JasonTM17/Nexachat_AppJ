package com.nexachat.feature.label;

import java.util.List;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PATCH;
import retrofit2.http.PUT;
import retrofit2.http.Path;

/** Retrofit endpoints for caller-owned conversation labels. */
public interface LabelApi {
    @GET("api/v1/labels")
    Call<List<LabelDto>> listLabels();

    @POST("api/v1/labels")
    Call<LabelDto> createLabel(@Body LabelWriteRequest body);

    @PATCH("api/v1/labels/{labelId}")
    Call<LabelDto> updateLabel(@Path("labelId") String labelId, @Body LabelWriteRequest body);

    @DELETE("api/v1/labels/{labelId}")
    Call<ResponseBody> deleteLabel(@Path("labelId") String labelId);

    @PUT("api/v1/conversations/{conversationId}/labels/{labelId}")
    Call<ResponseBody> assignLabel(
            @Path("conversationId") String conversationId,
            @Path("labelId") String labelId);

    @DELETE("api/v1/conversations/{conversationId}/labels/{labelId}")
    Call<ResponseBody> unassignLabel(
            @Path("conversationId") String conversationId,
            @Path("labelId") String labelId);
}

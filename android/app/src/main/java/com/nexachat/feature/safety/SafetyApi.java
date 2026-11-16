package com.nexachat.feature.safety;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Retrofit endpoints for user blocks, abuse reports, and starred messages. */
public interface SafetyApi {

    @POST("api/v1/users/{id}/block")
    Call<ResponseBody> blockUser(@Path("id") String userId);

    @DELETE("api/v1/users/{id}/block")
    Call<ResponseBody> unblockUser(@Path("id") String userId);

    @GET("api/v1/users/{id}/block")
    Call<BlockStatusDto> blockStatus(@Path("id") String userId);

    @GET("api/v1/users/blocked")
    Call<BlockedPageDto> listBlocked(
            @Query("limit") int limit,
            @Query("cursor") String cursor);

    @POST("api/v1/reports")
    Call<ReportDto> createReport(@Body ReportRequest body);

    @GET("api/v1/reports/mine")
    Call<ReportPageDto> listMyReports(
            @Query("limit") int limit,
            @Query("cursor") String cursor);

    @PUT("api/v1/messages/{id}/star")
    Call<ResponseBody> starMessage(@Path("id") String messageId);

    @DELETE("api/v1/messages/{id}/star")
    Call<ResponseBody> unstarMessage(@Path("id") String messageId);

    @GET("api/v1/messages/starred")
    Call<StarredPageDto> listStarred(
            @Query("limit") int limit,
            @Query("cursor") String cursor);
}

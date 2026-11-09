package com.nexachat.feature.archive;

import androidx.annotation.Nullable;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Authenticated Retrofit contract for conversation archive pages. */
public interface ArchiveApi {

    @GET("api/v1/conversations/{id}/archive")
    Call<ArchivePage> loadArchive(
            @Path("id") String conversationId,
            @Query("type") String type,
            @Nullable @Query("senderId") String senderId,
            @Nullable @Query("from") String from,
            @Nullable @Query("to") String to,
            @Query("limit") int limit,
            @Nullable @Query("cursor") String cursor);
}

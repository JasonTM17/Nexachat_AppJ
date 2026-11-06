package com.nexachat.core.network;

import com.nexachat.core.network.dto.AttachmentDto;
import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;

/**
 * {@code /api/v1/attachments} endpoints. Upload is multipart part name {@code file} ({@code
 * AttachmentDto}); content is authorized member-or-owner server-side, so every call must use the
 * auth-intercepted OkHttp client (the Retrofit instance already does). Same {@link Call} convention
 * as the other APIs — repositories pass each call to {@code safeApiCall(Call)}.
 */
public interface AttachmentApi {

    @Multipart
    @POST("api/v1/attachments")
    Call<AttachmentDto> upload(@Part MultipartBody.Part file);

    /** Raw bytes; the server sets a sniffed Content-Type. */
    @GET("api/v1/attachments/{id}/content")
    Call<ResponseBody> content(@Path("id") String attachmentId);
}

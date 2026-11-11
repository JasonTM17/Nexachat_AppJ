package com.nexachat.core.network;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;

/** Readiness endpoint used to wait for a sleeping API before sending auth mutations. */
public interface HealthApi {
    @GET("actuator/health")
    Call<ResponseBody> health();
}

package com.nexachat.core.network;

import com.nexachat.core.network.dto.FirebaseBridgeResponse;
import com.nexachat.core.network.dto.FirebaseLeaseRequest;
import com.nexachat.core.network.dto.FirebaseLeaseResponse;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.Call;

/** Authenticated S1 bridge endpoints for the optional Firebase ephemeral transport. */
public interface FirebaseRealtimeApi {
    @POST("api/v1/realtime/firebase-token")
    Call<FirebaseBridgeResponse> issueBridge();

    @POST("api/v1/realtime/firebase-lease")
    Call<FirebaseLeaseResponse> renewLease(@Body FirebaseLeaseRequest request);

    @DELETE("api/v1/realtime/firebase-session/{bridgeId}")
    Call<Void> revokeBridge(@Path("bridgeId") String bridgeId);
}

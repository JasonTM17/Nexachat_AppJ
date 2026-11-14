package com.nexachat.core.network;

import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.DeviceDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.RegisterDeviceRequest;
import com.nexachat.core.network.dto.E2eeDeviceDtos.SetE2eeRequest;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.PUT;
import retrofit2.http.Path;

/** E2EE device registry and direct-conversation toggle endpoints. */
public interface E2eeApi {

    /** Registers or re-confirms this installation's X25519 public key. */
    @PUT("api/v1/e2ee/devices/{deviceId}")
    Call<DeviceDto> registerDevice(
            @Path("deviceId") String deviceId,
            @Body RegisterDeviceRequest request);

    /** Revokes a device of the signed-in user; idempotent. */
    @DELETE("api/v1/e2ee/devices/{deviceId}")
    Call<Void> revokeDevice(@Path("deviceId") String deviceId);

    /** Current E2EE flag plus every member's active devices. */
    @GET("api/v1/conversations/{id}/e2ee")
    Call<ConversationE2eeDto> getE2ee(@Path("id") String conversationId);

    /** Toggles E2EE on a DIRECT conversation; enabling needs all members keyed. */
    @PUT("api/v1/conversations/{id}/e2ee")
    Call<ConversationE2eeDto> setE2ee(
            @Path("id") String conversationId,
            @Body SetE2eeRequest request);
}

package com.nexachat.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.squareup.moshi.Json;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.HTTP;
import retrofit2.http.POST;

/** Retrofit endpoints for the authenticated FCM device-token contract. */
public interface DeviceTokenApi {

    @POST("api/v1/users/device-token")
    Call<RegistrationResponse> register(@Body RegistrationRequest body);

    @HTTP(method = "DELETE", path = "api/v1/users/device-token", hasBody = true)
    Call<Void> unregister(
            @Body UnregisterRequest body,
            @Nullable @Header("Authorization") String authorization);

    /** Request shape shared with the server's {@code DeviceTokenRequest}. */
    final class RegistrationRequest {
        @Json(name = "token")
        private final String token;

        @Json(name = "platform")
        private final String platform;

        @Json(name = "deviceLabel")
        @Nullable private final String deviceLabel;

        public RegistrationRequest(@NonNull String token) {
            this(token, "ANDROID", null);
        }

        RegistrationRequest(
                @NonNull String token,
                @NonNull String platform,
                @Nullable String deviceLabel) {
            this.token = token;
            this.platform = platform;
            this.deviceLabel = deviceLabel;
        }

        @NonNull public String getToken() {
            return token;
        }

        @NonNull public String getPlatform() {
            return platform;
        }

        @Nullable public String getDeviceLabel() {
            return deviceLabel;
        }
    }

    /** The DELETE body avoids placing the registration credential in a request URL. */
    final class UnregisterRequest {
        @Json(name = "token")
        private final String token;

        public UnregisterRequest(@NonNull String token) {
            this.token = token;
        }

        @NonNull public String getToken() {
            return token;
        }
    }

    /** The server's 200 response shape; field names are pinned for reflective Moshi/R8 use. */
    final class RegistrationResponse {
        @Json(name = "id") private String id;
        @Json(name = "token") private String token;
        @Json(name = "platform") private String platform;
        @Json(name = "deviceLabel") @Nullable private String deviceLabel;
        @Json(name = "updatedAt") private String updatedAt;

        public RegistrationResponse() { }

        @NonNull public String getId() {
            return id;
        }

        @NonNull public String getToken() {
            return token;
        }

        @NonNull public String getPlatform() {
            return platform;
        }

        @Nullable public String getDeviceLabel() {
            return deviceLabel;
        }

        @NonNull public String getUpdatedAt() {
            return updatedAt;
        }
    }
}

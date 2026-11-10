package com.nexachat.core.network;

import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.ChangePasswordRequest;
import com.nexachat.core.network.dto.LoginRequest;
import com.nexachat.core.network.dto.LogoutRequest;
import com.nexachat.core.network.dto.RefreshRequest;
import com.nexachat.core.network.dto.PasswordResetConfirmRequest;
import com.nexachat.core.network.dto.PasswordResetRequest;
import com.nexachat.core.network.dto.RegisterRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.PATCH;
import retrofit2.http.POST;

/** Retrofit endpoints for {@code /api/v1/auth/…}. */
public interface AuthApi {

    Set<String> PUBLIC_PATHS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset/confirm"
    )));

    @POST("api/v1/auth/register")
    Call<AuthResponse> register(@Body RegisterRequest body);

    @POST("api/v1/auth/login")
    Call<AuthResponse> login(@Body LoginRequest body);

    @POST("api/v1/auth/refresh")
    Call<AuthResponse> refresh(@Body RefreshRequest body);

    @POST("api/v1/auth/refresh")
    Call<AuthResponse> refresh(
            @Header("Idempotency-Key") String idempotencyKey, @Body RefreshRequest body);

    @POST("api/v1/auth/logout")
    Call<Void> logout(@Body LogoutRequest body);

    @POST("api/v1/auth/logout-all")
    Call<Void> logoutAll();

    @PATCH("api/v1/auth/password")
    Call<Void> changePassword(@Body ChangePasswordRequest body);

    @POST("api/v1/auth/password-reset/request")
    Call<Void> requestPasswordReset(@Body PasswordResetRequest body);

    @POST("api/v1/auth/password-reset/confirm")
    Call<Void> confirmPasswordReset(@Body PasswordResetConfirmRequest body);

    /** Exact matches keep unrelated paths from becoming public accidentally. */
    static boolean isPublicAuthPath(String encodedPath) {
        return PUBLIC_PATHS.contains(encodedPath);
    }
}

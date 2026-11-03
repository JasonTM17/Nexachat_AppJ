package com.nexachat.core.network

import com.nexachat.core.network.dto.AuthResponse
import com.nexachat.core.network.dto.ChangePasswordRequest
import com.nexachat.core.network.dto.LoginRequest
import com.nexachat.core.network.dto.LogoutRequest
import com.nexachat.core.network.dto.RefreshRequest
import com.nexachat.core.network.dto.RegisterRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PATCH
import retrofit2.http.POST

/**
 * `/api/v1/auth/…` endpoints (plan §10.2). `Response<T>` (not `Call`/`T`) so
 * callers can read the status code and parse the error envelope themselves.
 */
interface AuthApi {

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<AuthResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<AuthResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    @POST("api/v1/auth/logout-all")
    suspend fun logoutAll(): Response<Unit>

    @PATCH("api/v1/auth/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Response<Unit>

    companion object {
        /**
         * Auth endpoints that must NOT carry `Authorization` and whose 401s are
         * real answers (e.g. bad credentials), not refresh triggers. Exact-match
         * on full paths — a suffix match could silently exempt a future route
         * that merely ends the same way.
         */
        val PUBLIC_PATHS = setOf(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
        )

        fun isPublicAuthPath(encodedPath: String): Boolean =
            encodedPath in PUBLIC_PATHS
    }
}

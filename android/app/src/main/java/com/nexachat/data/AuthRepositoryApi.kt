package com.nexachat.data

import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.AuthResponse

/** Coroutine-facing auth API implemented by the Java repository. */
interface AuthRepositoryApi {

    suspend fun register(
        phone: String,
        password: String,
        displayName: String,
    ): Result<AuthResponse>

    suspend fun login(phone: String, password: String): Result<AuthResponse>

    suspend fun refresh(): Result<Boolean>

    suspend fun logout(): Result<Unit>

    suspend fun logoutAll(): Result<Unit>

    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit>
}

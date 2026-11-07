package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.UserApi
import com.nexachat.core.network.dto.UpdateUserSettingsRequest
import com.nexachat.core.network.ApiResultKt
import com.nexachat.core.storage.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.asFlow
import retrofit2.Call

/** Kotlin coroutine/session collectors for [SettingsRepository]. */
internal object SettingsRepositoryFlow {

    @JvmStatic
    fun startCollectors(
        scope: CoroutineScope,
        tokenStore: TokenStore,
        sessionEvents: SessionEvents,
        showPresence: MutableStateFlow<Boolean>,
        repository: SettingsRepositoryApi,
    ) {
        scope.launch {
            tokenStore.hasValidRefresh.collect { hasSession ->
                if (hasSession) {
                    repository.refreshShowPresence()
                } else {
                    showPresence.value = true
                }
            }
        }
        scope.launch {
            sessionEvents.events.toObservable().asFlow().collect { event ->
                if (event is SessionEvents.Event.LoggedOut) showPresence.value = true
            }
        }
    }

    @JvmStatic
    suspend fun <T> call(retrofitCall: Call<T>): Result<T> =
        when (val result = ApiResultKt.safeApiCall(retrofitCall).await()) {
            is ApiResult.Success -> Result.Success(result.body)
            is ApiResult.HttpError ->
                Result.failure(ApiError.from(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.failure(ApiError.Network(result.cause.message))
        }

    @JvmStatic
    suspend fun refreshShowPresence(
        userApi: UserApi,
        showPresence: MutableStateFlow<Boolean>,
    ): Result<Unit> =
        when (val result = call(userApi.getSettings())) {
            is Result.Success -> {
                result.value.showPresence?.let { showPresence.value = it }
                Result.Success(Unit)
            }
            is Result.Failure -> Result.cast(result)
        }

    @JvmStatic
    suspend fun setShowPresence(
        userApi: UserApi,
        showPresence: MutableStateFlow<Boolean>,
        value: Boolean,
    ): Result<Unit> {
        val previous = showPresence.value
        if (previous == value) return Result.Success(Unit)
        showPresence.value = value
        return when (val result = call(userApi.updateSettings(UpdateUserSettingsRequest(value)))) {
            is Result.Success -> {
                result.value.showPresence?.let { showPresence.value = it }
                Result.Success(Unit)
            }
            is Result.Failure -> {
                showPresence.value = previous
                Result.cast(result)
            }
        }
    }
}

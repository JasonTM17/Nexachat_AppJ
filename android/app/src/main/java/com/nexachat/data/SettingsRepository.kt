package com.nexachat.data

import android.content.Context
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.UserApi
import com.nexachat.core.network.dto.UpdateUserSettingsRequest
import com.nexachat.core.network.safeApiCall
import com.nexachat.core.storage.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-wide appearance. SYSTEM follows the device; LIGHT/DARK pin one scheme. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        /** Unknown/absent stored value → [SYSTEM] (matches the locale rule). */
        fun fromName(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * Local preferences (theme) plus the server-backed privacy flag (F3).
 *
 * Theme is device-level and never cleared on logout. Presence visibility is
 * account-level: it is fetched once a session exists, updated optimistically
 * (revert on failure) and reset to the permissive default when the session
 * ends, so one account's privacy choice never leaks into the next login.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
    private val scope: CoroutineScope,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        ThemeMode.fromName(prefs.getString(KEY_THEME, null)),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _showPresence = MutableStateFlow(true)
    val showPresence: StateFlow<Boolean> = _showPresence.asStateFlow()

    init {
        scope.launch {
            tokenStore.hasValidRefresh.collect { hasSession ->
                if (hasSession) refreshShowPresence() else _showPresence.value = true
            }
        }
        scope.launch {
            sessionEvents.events.collect { event ->
                if (event is SessionEvents.Event.LoggedOut) _showPresence.value = true
            }
        }
    }

    /** Persists immediately — the root theme collector sees it on the next frame. */
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    /** Pulls the authoritative flag; a failure keeps the current value. */
    suspend fun refreshShowPresence(): Result<Unit> =
        when (val result = call { userApi.getSettings() }) {
            is Result.Success -> {
                result.value.showPresence?.let { _showPresence.value = it }
                Result.Success(Unit)
            }
            is Result.Failure -> Result.cast(result)
        }

    /**
     * Optimistic privacy toggle. The UI flips instantly; a rejection restores
     * the previous value so the switch never lies about the server state.
     */
    suspend fun setShowPresence(value: Boolean): Result<Unit> {
        val previous = _showPresence.value
        if (previous == value) return Result.Success(Unit)
        _showPresence.value = value
        return when (val result = call { userApi.updateSettings(UpdateUserSettingsRequest(value)) }) {
            is Result.Success -> {
                result.value.showPresence?.let { _showPresence.value = it }
                Result.Success(Unit)
            }
            is Result.Failure -> {
                _showPresence.value = previous
                Result.cast(result)
            }
        }
    }

    private suspend fun <T> call(
        block: suspend () -> retrofit2.Response<T>,
    ): Result<T> =
        when (val result = safeApiCall(block)) {
            is ApiResult.Success -> Result.Success(result.body)
            is ApiResult.HttpError ->
                Result.failure(ApiError.from(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.failure(ApiError.Network(result.cause.message))
        }

    private companion object {
        const val PREFS = "nexachat_settings"
        const val KEY_THEME = "theme_mode"
    }
}

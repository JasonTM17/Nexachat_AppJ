package com.nexachat.data

import com.nexachat.core.common.Result
import kotlinx.coroutines.flow.StateFlow

/** Coroutine-facing settings API implemented by the Java repository. */
interface SettingsRepositoryApi {
    val themeMode: StateFlow<ThemeMode>
    val showPresence: StateFlow<Boolean>

    fun setThemeMode(mode: ThemeMode)

    suspend fun refreshShowPresence(): Result<Unit>

    suspend fun setShowPresence(value: Boolean): Result<Unit>
}

package com.nexachat.feature.profile

import androidx.lifecycle.ViewModel
import com.nexachat.data.SettingsRepository
import com.nexachat.data.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Root-level appearance state. The activity hosts it and passes
 * [themeMode] to `NexaChatTheme`, so a settings change re-themes the whole
 * app without recreating activities or touching the locale mechanism.
 */
@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode

    fun setThemeMode(mode: ThemeMode) = settingsRepository.setThemeMode(mode)
}

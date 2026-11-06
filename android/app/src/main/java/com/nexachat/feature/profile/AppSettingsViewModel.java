package com.nexachat.feature.profile;

import androidx.lifecycle.ViewModel;
import com.nexachat.data.SettingsRepository;
import com.nexachat.data.ThemeMode;
import dagger.hilt.android.lifecycle.HiltViewModel;
import javax.inject.Inject;
import kotlinx.coroutines.flow.StateFlow;

/** Root appearance state. The activity reads themeMode and recomposes. */
@HiltViewModel
public final class AppSettingsViewModel extends ViewModel {

    private final SettingsRepository settingsRepository;

    @Inject
    public AppSettingsViewModel(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public StateFlow<ThemeMode> getThemeMode() {
        return settingsRepository.getThemeMode();
    }

    public void setThemeMode(ThemeMode mode) {
        settingsRepository.setThemeMode(mode);
    }
}

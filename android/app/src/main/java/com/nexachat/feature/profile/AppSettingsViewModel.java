package com.nexachat.feature.profile;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.data.ChatColor;
import com.nexachat.data.SettingsRepository;
import com.nexachat.data.ThemeMode;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import javax.inject.Inject;

/** Root appearance state. The host activity observes themeMode and updates night mode. */
@HiltViewModel
public final class AppSettingsViewModel extends ViewModel {

    private final SettingsRepository settingsRepository;
    private final MutableLiveData<ThemeMode> themeMode = new MutableLiveData<>(ThemeMode.SYSTEM);
    private final MutableLiveData<ChatColor> chatColor = new MutableLiveData<>(ChatColor.PLUM);
    /**
     * Seeded synchronously: a FALSE placeholder would reach the host's
     * observer before the posted stored value and force a needless recreate
     * on every cold start with pure black enabled.
     */
    private final MutableLiveData<Boolean> amoledBlack;
    private final CompositeDisposable disposables = new CompositeDisposable();

    @Inject
    public AppSettingsViewModel(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
        this.amoledBlack = new MutableLiveData<>(settingsRepository.isAmoledBlack());
        disposables.add(
                settingsRepository.getThemeMode()
                        .subscribe(themeMode::postValue, ignored -> { }));
        disposables.add(
                settingsRepository.getChatColor()
                        .subscribe(chatColor::postValue, ignored -> { }));
        disposables.add(
                settingsRepository.getAmoledBlack()
                        .subscribe(amoledBlack::postValue, ignored -> { }));
    }

    public LiveData<ThemeMode> getThemeMode() {
        return themeMode;
    }

    public void setThemeMode(ThemeMode mode) {
        settingsRepository.setThemeMode(mode);
    }

    public LiveData<ChatColor> getChatColor() {
        return chatColor;
    }

    public void setChatColor(ChatColor color) {
        settingsRepository.setChatColor(color);
    }

    public LiveData<Boolean> getAmoledBlack() {
        return amoledBlack;
    }

    public void setAmoledBlack(boolean enabled) {
        settingsRepository.setAmoledBlack(enabled);
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}

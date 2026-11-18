package com.nexachat.feature.streak;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.Result;
import com.nexachat.data.SettingsRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import javax.inject.Inject;

/**
 * The streak-reminder switch. The repository flips its value at once and rolls it back when the
 * server rejects the change; a rollback is announced through {@link #getUpdateFailed()}.
 */
@HiltViewModel
public final class StreakSettingsViewModel extends ViewModel {

    private final SettingsRepository settings;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<Boolean> reminders = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> updateFailed = new MutableLiveData<>();

    @Inject
    public StreakSettingsViewModel(@NonNull SettingsRepository settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        disposables.add(settings.getStreakReminders()
                .subscribeOn(Schedulers.io())
                .subscribe(reminders::postValue, error -> { }));
    }

    public LiveData<Boolean> getReminders() {
        return reminders;
    }

    /** True once after a change could not be saved; consume with {@link #onUpdateFailureShown()}. */
    public LiveData<Boolean> getUpdateFailed() {
        return updateFailed;
    }

    public void setReminders(boolean enabled) {
        disposables.add(settings.setStreakReminders(enabled)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> {
                            if (result instanceof Result.Failure<?>) {
                                updateFailed.postValue(true);
                            }
                        },
                        error -> updateFailed.postValue(true)));
    }

    /** Re-reads the server value, for example when the profile opens. */
    public void refresh() {
        disposables.add(settings.refreshSettings()
                .subscribeOn(Schedulers.io())
                .subscribe(result -> { }, error -> { }));
    }

    public void onUpdateFailureShown() {
        updateFailed.setValue(null);
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}

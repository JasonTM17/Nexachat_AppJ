package com.nexachat.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.storage.TokenStore;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * Local preferences (theme) plus the server-backed privacy flag.
 *
 * <p>Theme is device-level and never cleared on logout. Presence visibility is
 * account-level and resets to the permissive default when the session ends.
 */
@Singleton
public final class SettingsRepository implements SettingsRepositoryApi {

    private static final String PREFS = "nexachat_settings";
    private static final String KEY_THEME = "theme_mode";

    private final UserApi userApi;
    private final SharedPreferences prefs;
    private final MutableStateFlow<ThemeMode> mutableThemeMode;
    private final StateFlow<ThemeMode> themeMode;
    private final MutableStateFlow<Boolean> mutableShowPresence =
            StateFlowKt.MutableStateFlow(Boolean.TRUE);
    private final StateFlow<Boolean> showPresence = mutableShowPresence;

    @Inject
    public SettingsRepository(
            @ApplicationContext @NonNull Context context,
            @NonNull UserApi userApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull CoroutineScope scope) {
        this.userApi = userApi;
        this.prefs =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.mutableThemeMode =
                StateFlowKt.MutableStateFlow(ThemeMode.fromName(prefs.getString(KEY_THEME, null)));
        this.themeMode = mutableThemeMode;
        SettingsRepositoryFlow.startCollectors(
                scope, tokenStore, sessionEvents, mutableShowPresence, this);
    }

    @NonNull
    @Override
    public StateFlow<ThemeMode> getThemeMode() {
        return themeMode;
    }

    @NonNull
    @Override
    public StateFlow<Boolean> getShowPresence() {
        return showPresence;
    }

    @Override
    public void setThemeMode(@NonNull ThemeMode mode) {
        prefs.edit().putString(KEY_THEME, mode.name()).apply();
        mutableThemeMode.setValue(mode);
    }

    @Override
    public Object refreshShowPresence(
            @NonNull Continuation<? super Result<Unit>> completion) {
        return SettingsRepositoryFlow.refreshShowPresence(
                userApi, mutableShowPresence, completion);
    }

    @Override
    public Object setShowPresence(
            boolean value, @NonNull Continuation<? super Result<Unit>> completion) {
        return SettingsRepositoryFlow.setShowPresence(
                userApi, mutableShowPresence, value, completion);
    }
}

package com.nexachat.core.ui;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.FlowLiveDataConversions;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.core.common.AppLocaleManager;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.SessionEventsEntryPoint;
import com.nexachat.data.ThemeMode;
import com.nexachat.feature.profile.AppSettingsViewModel;
import dagger.hilt.android.AndroidEntryPoint;
import dagger.hilt.android.EntryPointAccessors;

/**
 * Single-activity XML host (B1 foundation). Compose {@link com.nexachat.MainActivity} remains the launcher until B4.
 * Locale and theme mirror {@link com.nexachat.MainActivity}; forced logout mirrors {@link AppNavHost}.
 */
@AndroidEntryPoint
public class NexaHostActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nexa_host);

        AppSettingsViewModel settings =
                new ViewModelProvider(this).get(AppSettingsViewModel.class);
        FlowLiveDataConversions.asLiveData(settings.getThemeMode())
                .observe(this, this::applyThemeMode);

        SessionEvents sessionEvents =
                EntryPointAccessors.fromApplication(
                                getApplicationContext(), SessionEventsEntryPoint.class)
                        .sessionEvents();
        FlowLiveDataConversions.asLiveData(sessionEvents.getEvents())
                .observe(this, this::onSessionEvent);
    }

    private void applyThemeMode(@Nullable ThemeMode mode) {
        int nightMode;
        if (mode == null || mode == ThemeMode.SYSTEM) {
            nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        } else if (mode == ThemeMode.LIGHT) {
            nightMode = AppCompatDelegate.MODE_NIGHT_NO;
        } else {
            nightMode = AppCompatDelegate.MODE_NIGHT_YES;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    private void onSessionEvent(@NonNull SessionEvents.Event event) {
        if (!(event instanceof SessionEvents.Event.LoggedOut)) {
            return;
        }
        NavHostFragment host =
                (NavHostFragment)
                        getSupportFragmentManager().findFragmentById(R.id.nav_host);
        if (host == null) {
            return;
        }
        NavController nav = host.getNavController();
        nav.navigate(R.id.action_global_login);
    }
}

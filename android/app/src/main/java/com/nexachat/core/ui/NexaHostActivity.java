package com.nexachat.core.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Single-activity XML Navigation host for NexaChat.
 */
@AndroidEntryPoint
public class NexaHostActivity extends AppCompatActivity {

    private final CompositeDisposable disposables = new CompositeDisposable();
    private boolean pendingLogoutNavigation;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nexa_host);

        View navHost = findViewById(R.id.nav_host);
        if (navHost != null) {
            ViewCompat.setOnApplyWindowInsetsListener(navHost, (view, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }

        AppSettingsViewModel settings =
                new ViewModelProvider(this).get(AppSettingsViewModel.class);
        settings.getThemeMode().observe(this, this::applyThemeMode);

        SessionEvents sessionEvents =
                EntryPointAccessors.fromApplication(
                                getApplicationContext(), SessionEventsEntryPoint.class)
                        .sessionEvents();
        disposables.add(
                sessionEvents.getEvents()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::onSessionEvent, ignored -> { }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingLogoutNavigation) {
            navigateToLoginIfPossible();
        }
    }

    @Override
    protected void onDestroy() {
        disposables.clear();
        super.onDestroy();
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
        if (getSupportFragmentManager().isStateSaved()) {
            pendingLogoutNavigation = true;
            return;
        }
        navigateToLoginIfPossible();
    }

    private void navigateToLoginIfPossible() {
        if (getSupportFragmentManager().isStateSaved()) {
            pendingLogoutNavigation = true;
            return;
        }
        NavHostFragment host =
                (NavHostFragment)
                        getSupportFragmentManager().findFragmentById(R.id.nav_host);
        if (host == null) {
            pendingLogoutNavigation = true;
            return;
        }
        NavController nav = host.getNavController();
        try {
            nav.navigate(R.id.action_global_login);
            pendingLogoutNavigation = false;
        } catch (IllegalStateException ignored) {
            pendingLogoutNavigation = true;
        }
    }
}

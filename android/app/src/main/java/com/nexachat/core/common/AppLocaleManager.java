package com.nexachat.core.common;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import androidx.annotation.Nullable;
import java.util.Locale;

/**
 * Per-app language apply/persist without AppCompat (absent from the dependency
 * graph, so {@code AppCompatDelegate.setApplicationLocales} is unavailable).
 *
 * <p>API 33+ uses {@link LocaleManager#setApplicationLocales}. Below that, the
 * tag lives in SharedPreferences and {@link #wrapContext} merges it at
 * {@code attachBaseContext}. SharedPreferences rather than DataStore because
 * attachBaseContext is synchronous.
 */
public final class AppLocaleManager {
    private static final String PREFS = "nexachat_locale";
    private static final String KEY_TAG = "language_tag";

    /**
     * Set only by JVM tests. Production leaves this null and reads real preferences.
     * Tests clear it when they finish.
     */
    @Nullable static SharedPreferences preferencesForTest;

    /**
     * JVM tests set this so {@code apply(SYSTEM)} does not touch a stub {@code Context}.
     * Production leaves it false.
     */
    static boolean useEmptySystemLocalesForTest;

    private AppLocaleManager() {
    }

    /** The active selection — framework value on 33+, stored pref below. */
    public static LanguageOption current(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager localeManager = context.getSystemService(LocaleManager.class);
            String tags = localeManager == null
                    ? null
                    : localeManager.getApplicationLocales().toLanguageTags();
            return LanguageOption.fromTag(tags);
        }
        return LanguageOption.fromTag(prefs(context).getString(KEY_TAG, null));
    }

    /**
     * Applies {@code option} immediately and makes it survive restarts. On 33+
     * the platform persists and reconfigures activities; below that the tag is
     * stored and the current activity is recreated so {@link #wrapContext}
     * rebuilds it in the new locale.
     */
    public static void apply(Context context, LanguageOption option) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager localeManager = context.getSystemService(LocaleManager.class);
            if (localeManager != null) {
                localeManager.setApplicationLocales(localeList(option));
            }
            return;
        }
        prefs(context).edit().putString(KEY_TAG, option.getTag()).apply();
        // java.util formatters consult Locale.getDefault(), not the activity configuration.
        Locale target = null;
        if (option == LanguageOption.SYSTEM) {
            LocaleList locales = systemLocales(context);
            if (locales != null && !locales.isEmpty()) {
                target = locales.get(0);
            }
        } else {
            target = Locale.forLanguageTag(option.getTag());
        }
        if (target != null) {
            Locale.setDefault(target);
        }
        Activity activity = findActivity(context);
        if (activity != null) {
            activity.recreate();
        }
    }

    /**
     * {@code attachBaseContext} hook for API &lt;33. Returns {@code base} untouched
     * on 33+ and when SYSTEM is selected.
     */
    public static Context wrapContext(Context base) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return base;
        }
        LanguageOption option = LanguageOption.fromTag(prefs(base).getString(KEY_TAG, null));
        if (option == LanguageOption.SYSTEM) {
            return base;
        }
        Locale.setDefault(locale(option));
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocales(localeList(option));
        return base.createConfigurationContext(config);
    }

    private static SharedPreferences prefs(Context context) {
        if (preferencesForTest != null) {
            return preferencesForTest;
        }
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    private static LocaleList systemLocales(Context context) {
        if (useEmptySystemLocalesForTest || context == null) {
            return null;
        }
        return context.getResources().getConfiguration().getLocales();
    }

    private static Locale locale(LanguageOption option) {
        return Locale.forLanguageTag(option.getTag());
    }

    private static LocaleList localeList(LanguageOption option) {
        if (option.getTag().isEmpty()) {
            return LocaleList.getEmptyLocaleList();
        }
        return LocaleList.forLanguageTags(option.getTag());
    }

    /** Walks ContextWrapper layers to the Activity; null for app contexts. */
    @Nullable
    private static Activity findActivity(@Nullable Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity activity) {
                return activity;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}

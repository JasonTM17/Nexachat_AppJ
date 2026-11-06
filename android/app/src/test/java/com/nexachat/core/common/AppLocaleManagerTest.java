package com.nexachat.core.common;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure tag mapping and the SharedPreferences-backed apply/current round-trip.
 * Plain JVM tests have {@code Build.VERSION.SDK_INT == 0}, so {@link AppLocaleManager}
 * takes its API &lt;33 branch. The 33+ framework path is exercised on device.
 */
public final class AppLocaleManagerTest {
    private Locale defaultLocale;

    @Before
    public void keepLocale() {
        defaultLocale = Locale.getDefault();
        AppLocaleManager.preferencesForTest = null;
        AppLocaleManager.useEmptySystemLocalesForTest = false;
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(defaultLocale);
        AppLocaleManager.preferencesForTest = null;
        AppLocaleManager.useEmptySystemLocalesForTest = false;
    }

    @Test
    public void fromTagMapsEnAndViToTheirOptions() {
        assertEquals(LanguageOption.ENGLISH, LanguageOption.fromTag("en"));
        assertEquals(LanguageOption.VIETNAMESE, LanguageOption.fromTag("vi"));
    }

    @Test
    public void fromTagMapsNullBlankAndUnknownTagsToSystem() {
        assertEquals(LanguageOption.SYSTEM, LanguageOption.fromTag(null));
        assertEquals(LanguageOption.SYSTEM, LanguageOption.fromTag(""));
        assertEquals(LanguageOption.SYSTEM, LanguageOption.fromTag("fr"));
    }

    @Test
    public void currentDefaultsToSystemWhenNothingWasStored() {
        AppLocaleManager.preferencesForTest = new MemoryPrefs();
        assertEquals(LanguageOption.SYSTEM, AppLocaleManager.current(null));
    }

    @Test
    public void applyPersistsTheTagAndCurrentReadsItBack() {
        AppLocaleManager.preferencesForTest = new MemoryPrefs();

        AppLocaleManager.apply(null, LanguageOption.VIETNAMESE);
        assertEquals(LanguageOption.VIETNAMESE, AppLocaleManager.current(null));

        AppLocaleManager.apply(null, LanguageOption.ENGLISH);
        assertEquals(LanguageOption.ENGLISH, AppLocaleManager.current(null));
    }

    @Test
    public void applySystemClearsBackToTheSystemOption() {
        AppLocaleManager.preferencesForTest = new MemoryPrefs();
        AppLocaleManager.useEmptySystemLocalesForTest = true;
        AppLocaleManager.apply(null, LanguageOption.VIETNAMESE);

        AppLocaleManager.apply(null, LanguageOption.SYSTEM);

        assertEquals(LanguageOption.SYSTEM, AppLocaleManager.current(null));
    }

    /** In-memory stand-in for the nexachat_locale preferences file. */
    private static final class MemoryPrefs implements SharedPreferences {
        private final Map<String, String> store = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(store);
        }

        @Override
        public String getString(String key, String defValue) {
            return store.containsKey(key) ? store.get(key) : defValue;
        }

        @Override
        public Editor edit() {
            return new Editor();
        }

        private final class Editor implements SharedPreferences.Editor {
            @Override
            public SharedPreferences.Editor putString(String key, String value) {
                store.put(key, value);
                return this;
            }

            @Override
            public SharedPreferences.Editor remove(String key) {
                store.remove(key);
                return this;
            }

            @Override
            public SharedPreferences.Editor clear() {
                store.clear();
                return this;
            }

            @Override
            public boolean commit() {
                return true;
            }

            @Override
            public void apply() {
            }

            @Override
            public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
                return this;
            }

            @Override
            public SharedPreferences.Editor putInt(String key, int value) {
                return this;
            }

            @Override
            public SharedPreferences.Editor putLong(String key, long value) {
                return this;
            }

            @Override
            public SharedPreferences.Editor putFloat(String key, float value) {
                return this;
            }

            @Override
            public SharedPreferences.Editor putBoolean(String key, boolean value) {
                return this;
            }
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            return defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            return defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            return defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return defValue;
        }

        @Override
        public boolean contains(String key) {
            return store.containsKey(key);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }
}

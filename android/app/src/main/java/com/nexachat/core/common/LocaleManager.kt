package com.nexachat.core.common

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * A selectable per-app language (phase-10 P8). [tag] is the BCP-47 value sent
 * to the platform; an empty tag means "follow the system".
 */
enum class LanguageOption(val tag: String) {
    SYSTEM(""),
    VIETNAMESE("vi"),
    ENGLISH("en");

    companion object {
        /** Maps a stored/system tag back to an option; unknown or blank → [SYSTEM]. */
        fun fromTag(tag: String?): LanguageOption =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * Per-app language apply/persist without `androidx.appcompat` (deliberately
 * absent from the dependency graph, so AppCompatDelegate.setApplicationLocales
 * is unavailable). Two native mechanisms by API level:
 *
 * - API 33+: `android.app.LocaleManager.setApplicationLocales` — the system
 *   persists the choice, applies it to every activity via a configuration
 *   change, and restores it across cold starts. No app-side storage needed.
 * - API <33: the tag lives in a tiny SharedPreferences file and is merged
 *   into each activity's configuration by [wrapContext], which
 *   `MainActivity.attachBaseContext` calls. Changing the selection recreates
 *   the running activity. SharedPreferences rather than DataStore because
 *   attachBaseContext is synchronous — it cannot suspend on the first read.
 */
object AppLocaleManager {

    private const val PREFS = "nexachat_locale"
    private const val KEY_TAG = "language_tag"

    /** The active selection — framework value on 33+, stored pref below. */
    fun current(context: Context): LanguageOption =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LanguageOption.fromTag(
                context.getSystemService(LocaleManager::class.java)
                    ?.applicationLocales
                    ?.toLanguageTags(),
            )
        } else {
            LanguageOption.fromTag(prefs(context).getString(KEY_TAG, null))
        }

    /**
     * Applies [option] immediately and makes it survive restarts. On 33+ the
     * platform persists and reconfigures activities; below that the tag is
     * stored and the current activity is recreated so [wrapContext] rebuilds
     * it in the new locale. Pass an activity-scoped context (Compose's
     * LocalContext qualifies) so the <33 recreate can run.
     */
    fun apply(context: Context, option: LanguageOption) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = option.localeList()
        } else {
            prefs(context).edit().putString(KEY_TAG, option.tag).apply()
            // Align the JVM default too — java.util formatters
            // (SimpleDateFormat, String.format) consult Locale.getDefault(),
            // not the activity configuration.
            val target = if (option == LanguageOption.SYSTEM) {
                context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)
            } else {
                option.locale()
            }
            if (target != null) Locale.setDefault(target)
            context.findActivity()?.recreate()
        }
    }

    /**
     * `attachBaseContext` hook for API <33: merges the stored tag into the
     * activity's base configuration. Returns [base] untouched on 33+ (the
     * platform already configures the activity) and when SYSTEM is selected.
     */
    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val option = LanguageOption.fromTag(prefs(base).getString(KEY_TAG, null))
        if (option == LanguageOption.SYSTEM) return base
        Locale.setDefault(option.locale())
        val config = Configuration(base.resources.configuration)
        config.setLocales(option.localeList())
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun LanguageOption.locale(): Locale = Locale.forLanguageTag(tag)

    private fun LanguageOption.localeList(): LocaleList =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)

    /** Walks ContextWrapper layers to the Activity; null for app contexts. */
    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

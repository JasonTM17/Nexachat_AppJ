package com.nexachat.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Non-secret app preferences (locale override today; theme/notification
 * settings later). Lives in a *separate* DataStore file from [TokenStore] so
 * session wipes can never touch app settings and vice versa.
 */
@Singleton
class PrefsDataStore @Inject constructor(
    @AppPreferences private val dataStore: DataStore<Preferences>,
) {

    private object Keys {
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
    }

    /** BCP-47 tag chosen in Settings; `null` = follow system. */
    val languageTag: Flow<String?> =
        dataStore.data.map { it[Keys.LANGUAGE_TAG] }

    suspend fun setLanguageTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag == null) prefs.remove(Keys.LANGUAGE_TAG) else prefs[Keys.LANGUAGE_TAG] = tag
        }
    }
}

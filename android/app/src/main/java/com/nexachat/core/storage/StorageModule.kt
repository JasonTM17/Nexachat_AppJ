package com.nexachat.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** DataStore file holding encrypted session tokens ([TokenStore]). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TokenPreferences

private const val TOKEN_FILE = "nexachat_tokens"

/**
 * `PreferenceDataStoreFactory` (rather than the `preferencesDataStore`
 * delegate) keeps the file name explicit and the store injectable for
 * faking in tests. Locale override lives in AppLocaleManager, not here —
 * attachBaseContext cannot suspend on a DataStore read.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideStorageScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    @TokenPreferences
    fun provideTokenDataStore(
        @ApplicationContext context: Context,
        scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile(TOKEN_FILE) },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageBindingsModule {

    @Binds
    @Singleton
    abstract fun bindTokenCipher(impl: AesGcmTokenCipher): TokenCipher
}

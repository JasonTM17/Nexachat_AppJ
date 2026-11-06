package com.nexachat.core.storage;

import android.content.Context;
import androidx.datastore.core.DataMigration;
import androidx.datastore.core.DataStore;
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler;
import androidx.datastore.preferences.PreferenceDataStoreFile;
import androidx.datastore.preferences.core.PreferenceDataStoreFactory;
import androidx.datastore.preferences.core.Preferences;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import java.util.Collections;
import javax.inject.Singleton;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.SupervisorKt;

@Module
@InstallIn(SingletonComponent.class)
public final class StorageModule {

    private static final String TOKEN_FILE = "nexachat_tokens";

    private StorageModule() {}

    @Provides
    @Singleton
    public static CoroutineScope provideStorageScope() {
        return CoroutineScopeKt.CoroutineScope(
                SupervisorKt.SupervisorJob(null).plus(Dispatchers.getIO()));
    }

    @Provides
    @Singleton
    @TokenPreferences
    public static DataStore<Preferences> provideTokenDataStore(
            @ApplicationContext Context context, CoroutineScope scope) {
        return PreferenceDataStoreFactory.INSTANCE.create(
                (ReplaceFileCorruptionHandler<Preferences>) null,
                Collections.<DataMigration<Preferences>>emptyList(),
                scope,
                () -> PreferenceDataStoreFile.preferencesDataStoreFile(context, TOKEN_FILE));
    }
}

package com.nexachat.core.storage;

import android.content.Context;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import io.reactivex.rxjava3.schedulers.Schedulers;
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

    /** Shared IO scope still used by repositories and realtime services being converted later. */
    @Provides
    @Singleton
    public static CoroutineScope provideStorageScope() {
        return CoroutineScopeKt.CoroutineScope(
                SupervisorKt.SupervisorJob(null).plus(Dispatchers.getIO()));
    }

    @Provides
    @Singleton
    @TokenPreferences
    public static RxDataStore<Preferences> provideTokenDataStore(
            @ApplicationContext Context context) {
        return new RxPreferenceDataStoreBuilder(context, TOKEN_FILE)
                .setIoScheduler(Schedulers.io())
                .build();
    }
}

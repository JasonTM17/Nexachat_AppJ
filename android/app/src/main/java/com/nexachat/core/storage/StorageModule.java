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

@Module
@InstallIn(SingletonComponent.class)
public final class StorageModule {

    private static final String TOKEN_FILE = "nexachat_tokens";

    private StorageModule() {}

    @Provides
    @Singleton
    @TokenPreferences
    public static RxDataStore<Preferences> provideTokenDataStore(
            @ApplicationContext Context context) {
        return new RxPreferenceDataStoreBuilder(context, TOKEN_FILE)
                .setIoScheduler(Schedulers.io())
                .build();
    }

    @Provides
    @Singleton
    public static com.nexachat.core.crypto.DeviceKeyStore provideE2eeDeviceKeyStore(
            @ApplicationContext Context context, TokenCipher cipher) {
        return new com.nexachat.core.crypto.DeviceKeyStore(context, cipher);
    }
}

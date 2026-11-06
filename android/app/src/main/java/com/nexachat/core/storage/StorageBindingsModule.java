package com.nexachat.core.storage;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public abstract class StorageBindingsModule {

    @Binds
    @Singleton
    abstract TokenCipher bindTokenCipher(AesGcmTokenCipher impl);
}

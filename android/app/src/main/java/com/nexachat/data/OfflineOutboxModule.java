package com.nexachat.data;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public abstract class OfflineOutboxModule {
    @Binds
    @Singleton
    public abstract OfflineActionApi bindOfflineActionApi(RetrofitOfflineActionApi implementation);
}

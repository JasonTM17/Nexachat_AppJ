package com.nexachat.feature.streak;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

/** Hilt bindings for the streak network client, repository and local store. */
@Module
@InstallIn(SingletonComponent.class)
public abstract class StreakModule {

    @Binds
    @Singleton
    public abstract StreakRepository bindStreakRepository(RetrofitStreakRepository repository);

    @Binds
    @Singleton
    public abstract StreakStore bindStreakStore(PrefsStreakStore store);

    @Provides
    @Singleton
    public static StreakApi provideStreakApi(Retrofit retrofit) {
        return retrofit.create(StreakApi.class);
    }
}

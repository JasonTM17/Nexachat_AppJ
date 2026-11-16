package com.nexachat.feature.safety;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

@Module
@InstallIn(SingletonComponent.class)
public abstract class SafetyModule {
    @Binds
    @Singleton
    public abstract SafetyRepository bindSafetyRepository(RetrofitSafetyRepository repository);

    @Provides
    @Singleton
    public static SafetyApi provideSafetyApi(Retrofit retrofit) {
        return retrofit.create(SafetyApi.class);
    }
}

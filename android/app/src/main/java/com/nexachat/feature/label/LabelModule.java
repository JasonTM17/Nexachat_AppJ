package com.nexachat.feature.label;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

@Module
@InstallIn(SingletonComponent.class)
public abstract class LabelModule {
    @Binds
    @Singleton
    public abstract LabelRepository bindLabelRepository(RetrofitLabelRepository repository);

    @Provides
    @Singleton
    public static LabelApi provideLabelApi(Retrofit retrofit) {
        return retrofit.create(LabelApi.class);
    }
}

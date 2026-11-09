package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

/** Hilt bindings for the reminder board. */
@Module
@InstallIn(SingletonComponent.class)
public final class ReminderModule {

    private ReminderModule() { }

    @Provides
    @Singleton
    public static ReminderApi provideReminderApi(@NonNull Retrofit retrofit) {
        return retrofit.create(ReminderApi.class);
    }

    @Provides
    @Singleton
    public static ReminderRepository provideReminderRepository(@NonNull ReminderApi api) {
        return new RetrofitReminderRepository(api);
    }
}

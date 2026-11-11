package com.nexachat.di;

import com.nexachat.BuildConfig;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.realtime.ephemeral.EphemeralStateRouter;
import com.nexachat.core.realtime.ephemeral.FirebaseEphemeralStateSource;
import com.nexachat.core.realtime.ephemeral.StompEphemeralStateSource;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public final class EphemeralModule {
    private EphemeralModule() { }

    @Provides
    @Singleton
    public static EphemeralStateRouter provideEphemeralStateRouter(
            TokenRefresher tokenRefresher,
            SessionEvents sessionEvents,
            StompEphemeralStateSource stomp,
            FirebaseEphemeralStateSource firebase) {
        return new EphemeralStateRouter(tokenRefresher, sessionEvents, stomp, firebase,
                BuildConfig.FIREBASE_REALTIME_ENABLED);
    }
}

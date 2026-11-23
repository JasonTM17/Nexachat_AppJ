package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

/** Hilt bindings for scheduled messages. */
@Module
@InstallIn(SingletonComponent.class)
public final class ScheduledMessagesModule {

    private ScheduledMessagesModule() { }

    @Provides
    @Singleton
    public static ScheduledMessageApi provideScheduledMessageApi(@NonNull Retrofit retrofit) {
        return retrofit.create(ScheduledMessageApi.class);
    }

    @Provides
    @Singleton
    public static ScheduledMessageRepository provideScheduledMessageRepository(
            @NonNull ScheduledMessageApi api) {
        return new RetrofitScheduledMessageRepository(api);
    }

    @Provides
    @Singleton
    public static ScheduledMessageEventSource provideScheduledMessageEventSource(
            @NonNull RealtimeSession session) {
        return () -> session.getEvents()
                .filter(event -> event instanceof ChatEvent.ScheduledMessageUpdated)
                .map(event -> (ChatEvent.ScheduledMessageUpdated) event);
    }
}

package com.nexachat.di;

import android.content.Context;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallPeerNameResolver;
import com.nexachat.call.core.CallRepository;
import com.nexachat.call.core.CallSignalingClient;
import com.nexachat.call.core.RoomCallPeerNameResolver;
import com.nexachat.call.core.StompCallSignalingClient;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.network.stomp.StompClient;
import com.squareup.moshi.Moshi;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

/** Hilt bindings for the Java call core. */
@Module
@InstallIn(SingletonComponent.class)
public final class CallModule {
    private CallModule() { }

    @Provides
    @Singleton
    public static CallRepository.Api provideCallApi(Retrofit retrofit) {
        return retrofit.create(CallRepository.Api.class);
    }

    @Provides
    @Singleton
    public static CallRepository provideCallRepository(CallRepository.Api api, Moshi moshi) {
        return new CallRepository(api, moshi);
    }

    @Provides
    @Singleton
    public static CallPeerNameResolver provideCallPeerNameResolver(
            @ApplicationContext Context context,
            ConversationDao conversationDao) {
        return new RoomCallPeerNameResolver(context, conversationDao);
    }

    @Provides
    @Singleton
    public static CallSignalingClient provideCallSignalingClient(
            RealtimeSession realtimeSession,
            StompClient stompClient,
            Moshi moshi) {
        return new StompCallSignalingClient(realtimeSession, stompClient, moshi);
    }

    @Provides
    @Singleton
    public static CallManager provideCallManager(
            CallSignalingClient signalingClient,
            CallRepository repository,
            CallPeerNameResolver peerNameResolver) {
        return new CallManager(signalingClient, repository, peerNameResolver);
    }
}

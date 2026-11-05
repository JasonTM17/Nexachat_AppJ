package com.nexachat.di;

import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallRepository;
import com.nexachat.call.core.CallSignalingClient;
import com.nexachat.call.core.StompCallSignalingClient;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.network.stomp.StompClient;
import com.squareup.moshi.Moshi;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import kotlinx.coroutines.CoroutineScope;
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
    public static CallSignalingClient provideCallSignalingClient(
            RealtimeSession realtimeSession,
            StompClient stompClient,
            CoroutineScope scope,
            Moshi moshi) {
        return new StompCallSignalingClient(realtimeSession, stompClient, scope, moshi);
    }

    @Provides
    @Singleton
    public static CallManager provideCallManager(
            CallSignalingClient signalingClient,
            CallRepository repository) {
        return new CallManager(signalingClient, repository);
    }
}

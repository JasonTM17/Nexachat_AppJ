package com.nexachat.core.network;

import android.content.Context;
import androidx.annotation.NonNull;
import com.nexachat.core.media.MediaCacheConfig;
import com.nexachat.core.media.MediaCacheRequestInterceptor;
import com.nexachat.core.media.MediaCacheResponseInterceptor;
import com.nexachat.BuildConfig;
import com.nexachat.core.common.AppConfig;
import com.squareup.moshi.Moshi;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

@Module
@InstallIn(SingletonComponent.class)
public final class NetworkModule {
    private NetworkModule() { }

    @Provides
    @Singleton
    public static Moshi provideMoshi() {
        // REST and STOMP DTOs use Moshi's reflective Java bean adapters.
        return new Moshi.Builder()
                .add(new com.nexachat.core.network.dto.AttachmentDtoJsonAdapterFactory())
                .build();
    }

    @Provides
    @Singleton
    public static OkHttpClient provideOkHttpClient(
            @ApplicationContext @NonNull Context context,
            AuthInterceptor authInterceptor,
            TokenAuthenticator tokenAuthenticator) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                // 60s write window: attachment uploads may stream up to 25 MB.
                .writeTimeout(60, TimeUnit.SECONDS)
                .cache(MediaCacheConfig.createOkHttpCache(context.getCacheDir()))
                .addInterceptor(new MediaCacheRequestInterceptor())
                .addInterceptor(authInterceptor)
                .addInterceptor(new AiRequestTimeoutInterceptor())
                .addNetworkInterceptor(new MediaCacheResponseInterceptor())
                .authenticator(tokenAuthenticator);
        if (BuildConfig.DEBUG) {
            // BASIC only: never log auth bodies/headers (plan §13 "Sensitive log").
            HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);
            loggingInterceptor.redactHeader(AuthInterceptor.HEADER_AUTHORIZATION);
            builder.addInterceptor(loggingInterceptor);
        }
        return builder.build();
    }

    /** Foreground signal the reconnect loop uses to bound its parked re-arm. */
    @Provides
    @Singleton
    public static AppForegroundState provideAppForegroundState(ProcessForegroundState state) {
        return state;
    }

    @Provides
    @Singleton
    public static Retrofit provideRetrofit(OkHttpClient okHttpClient, Moshi moshi) {
        return new Retrofit.Builder()
                .baseUrl(HttpUrl.get(AppConfig.API_BASE_URL))
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build();
    }

    @Provides
    @Singleton
    public static AuthApi provideAuthApi(Retrofit retrofit) {
        return retrofit.create(AuthApi.class);
    }

    @Provides
    @Singleton
    public static HealthApi provideHealthApi() {
        // Readiness polling must not depend on TokenAuthenticator -> TokenRefresher
        // -> ServerWakeGate, and must not rotate credentials on a health response.
        OkHttpClient healthClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        Retrofit healthRetrofit = new Retrofit.Builder()
                .baseUrl(HttpUrl.get(AppConfig.API_BASE_URL))
                .client(healthClient)
                .build();
        return healthRetrofit.create(HealthApi.class);
    }

    @Provides
    @Singleton
    public static UserApi provideUserApi(Retrofit retrofit) {
        return retrofit.create(UserApi.class);
    }

    @Provides
    @Singleton
    public static MessageApi provideMessageApi(Retrofit retrofit) {
        return retrofit.create(MessageApi.class);
    }

    @Provides
    @Singleton
    public static GroupApi provideGroupApi(Retrofit retrofit) {
        return retrofit.create(GroupApi.class);
    }

    @Provides
    @Singleton
    public static DeviceTokenApi provideDeviceTokenApi(Retrofit retrofit) {
        return retrofit.create(DeviceTokenApi.class);
    }

    @Provides
    @Singleton
    public static AttachmentApi provideAttachmentApi(Retrofit retrofit) {
        return retrofit.create(AttachmentApi.class);
    }

    @Provides
    @Singleton
    public static ConversationSettingsApi provideConversationSettingsApi(Retrofit retrofit) {
        return retrofit.create(ConversationSettingsApi.class);
    }

    @Provides
    @Singleton
    public static FirebaseRealtimeApi provideFirebaseRealtimeApi(Retrofit retrofit) {
        return retrofit.create(FirebaseRealtimeApi.class);
    }
}

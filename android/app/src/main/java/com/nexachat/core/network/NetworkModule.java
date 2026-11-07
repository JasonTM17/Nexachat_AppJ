package com.nexachat.core.network;

import com.nexachat.BuildConfig;
import com.nexachat.core.common.AppConfig;
import com.squareup.moshi.Moshi;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
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
        return new Moshi.Builder().build();
    }

    @Provides
    @Singleton
    public static OkHttpClient provideOkHttpClient(
            AuthInterceptor authInterceptor,
            TokenAuthenticator tokenAuthenticator) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                // 60s write window: attachment uploads may stream up to 25 MB.
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor)
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
    public static AttachmentApi provideAttachmentApi(Retrofit retrofit) {
        return retrofit.create(AttachmentApi.class);
    }
}

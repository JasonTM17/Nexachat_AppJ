package com.nexachat.feature.invite;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;

/** Invite API uses the authenticated client without URL logging because tokens are path values. */
@Module
@InstallIn(SingletonComponent.class)
public final class InviteModule {
    private InviteModule() { }

    @Provides
    @Singleton
    public static InviteApi provideInviteApi(Retrofit retrofit, OkHttpClient authenticatedClient) {
        return retrofit.newBuilder()
                .client(withoutHttpLogging(authenticatedClient))
                .build()
                .create(InviteApi.class);
    }

    @Provides
    @Singleton
    public static InviteRepository provideInviteRepository(InviteApi api) {
        return new RetrofitInviteRepository(api);
    }

    static OkHttpClient withoutHttpLogging(OkHttpClient client) {
        OkHttpClient.Builder builder = client.newBuilder();
        builder.interceptors().removeIf(InviteModule::isHttpLogger);
        builder.networkInterceptors().removeIf(InviteModule::isHttpLogger);
        return builder.build();
    }

    private static boolean isHttpLogger(Interceptor interceptor) {
        return interceptor instanceof HttpLoggingInterceptor;
    }
}

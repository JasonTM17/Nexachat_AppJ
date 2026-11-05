package com.nexachat.core.network

import android.content.Context
import coil.ImageLoader
import com.nexachat.BuildConfig
import com.nexachat.core.common.AppConfig
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi =
        // No KotlinJsonAdapterFactory on the classpath — every DTO uses
        // @JsonClass(generateAdapter = true) (ksp codegen adapters).
        Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // 60s write window: attachment uploads may stream up to 25 MB.
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .apply {
            if (BuildConfig.DEBUG) {
                // BASIC only: never log auth bodies/headers (plan §13 "Sensitive log").
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader(AuthInterceptor.HEADER_AUTHORIZATION)
                    },
                )
            }
        }
        .build()

    /** Foreground signal the reconnect loop uses to bound its parked re-arm. */
    @Provides
    @Singleton
    fun provideAppForegroundState(state: ProcessForegroundState): AppForegroundState = state

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            // toHttpUrl() normalizes to a trailing '/', which Retrofit requires.
            .baseUrl(AppConfig.API_BASE_URL.toHttpUrl())
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)

    @Provides
    @Singleton
    fun provideMessageApi(retrofit: Retrofit): MessageApi = retrofit.create(MessageApi::class.java)

    @Provides
    @Singleton
    fun provideGroupApi(retrofit: Retrofit): GroupApi = retrofit.create(GroupApi::class.java)

    @Provides
    @Singleton
    fun provideAttachmentApi(retrofit: Retrofit): AttachmentApi =
        retrofit.create(AttachmentApi::class.java)

    /**
     * Coil loader wired to the auth-intercepted OkHttp client — attachment
     * content endpoints require `Authorization`, which the default loader
     * cannot supply. Inject/pass explicitly wherever media bytes are fetched.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(okHttpClient)
        .build()
}

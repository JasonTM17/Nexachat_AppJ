package com.nexachat.di;

import com.nexachat.summary.AiConsentApi;
import com.nexachat.summary.AiConsentRepository;
import com.nexachat.summary.SummaryApi;
import com.nexachat.summary.SummaryRepository;
import com.nexachat.summary.SummaryRequest;
import com.nexachat.summary.SummaryResponse;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.feature.ask.AskApi;
import com.nexachat.feature.ask.AskRepository;
import android.os.Handler;
import android.os.Looper;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import java.util.concurrent.Executor;
import javax.inject.Named;
import javax.inject.Singleton;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Retrofit;

/** Hilt bindings for the Java summary data layer. */
@Module
@InstallIn(SingletonComponent.class)
public final class SummaryModule {
    private SummaryModule() { }

    @Provides
    @Singleton
    @Named("summary_main")
    public static Executor provideSummaryMainExecutor() {
        Handler handler = new Handler(Looper.getMainLooper());
        return command -> handler.post(command);
    }

    @Provides
    @Singleton
    public static SummaryApi provideSummaryApi(Retrofit retrofit, OkHttpClient client) {
        SummaryApi automatic = retrofit.create(SummaryApi.class);
        // A redirect can preserve a POST and turn the local route into an ordinary summary.
        SummaryApi local = retrofit.newBuilder()
                .client(client.newBuilder().followRedirects(false).build())
                .build()
                .create(SummaryApi.class);
        return new SummaryApi() {
            @Override
            public Call<SummaryResponse> summarize(String conversationId, SummaryRequest request) {
                return automatic.summarize(conversationId, request);
            }

            @Override
            public Call<SummaryResponse> summarizeLocally(String conversationId, SummaryRequest request) {
                return local.summarizeLocally(conversationId, request);
            }
        };
    }

    @Provides
    @Singleton
    public static AiConsentApi provideAiConsentApi(Retrofit retrofit) {
        return retrofit.create(AiConsentApi.class);
    }

    @Provides
    @Singleton
    public static SummaryRepository provideSummaryRepository(SummaryApi api, SessionEvents sessionEvents) {
        return new SummaryRepository(api, sessionEvents);
    }

    @Provides
    @Singleton
    public static AiConsentRepository provideAiConsentRepository(
            AiConsentApi api,
            @Named("summary_main") Executor mainExecutor,
            SummaryRepository summaryRepository) {
        return new AiConsentRepository(api, mainExecutor, summaryRepository);
    }

    @Provides
    @Singleton
    public static AskApi provideAskApi(Retrofit retrofit) {
        return retrofit.create(AskApi.class);
    }

    @Provides
    @Singleton
    public static AskRepository provideAskRepository(AskApi api) {
        return new AskRepository(api);
    }
}

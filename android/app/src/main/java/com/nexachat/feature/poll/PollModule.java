package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import com.nexachat.data.ConversationRepository;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

/** Hilt bindings for the poll board. */
@Module
@InstallIn(SingletonComponent.class)
public final class PollModule {

    private PollModule() { }

    @Provides
    @Singleton
    public static PollApi providePollApi(@NonNull Retrofit retrofit) {
        return retrofit.create(PollApi.class);
    }

    @Provides
    @Singleton
    public static PollRepository providePollRepository(@NonNull PollApi api) {
        return new RetrofitPollRepository(api);
    }

    @Provides
    @Singleton
    public static PollViewerSource providePollViewerSource(
            @NonNull ConversationRepository conversationRepository) {
        return new ConversationPollViewerSource(conversationRepository);
    }
}

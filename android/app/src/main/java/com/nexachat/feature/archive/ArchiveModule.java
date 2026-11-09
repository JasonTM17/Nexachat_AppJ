package com.nexachat.feature.archive;

import com.nexachat.data.ConversationRepository;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

/** Hilt bindings for the archive screen's network and member sources. */
@Module
@InstallIn(SingletonComponent.class)
public final class ArchiveModule {

    private ArchiveModule() { }

    @Provides
    @Singleton
    public static ArchiveApi provideArchiveApi(Retrofit retrofit) {
        return retrofit.create(ArchiveApi.class);
    }

    @Provides
    @Singleton
    public static ArchiveRepository provideArchiveRepository(ArchiveApi api) {
        return new RetrofitArchiveRepository(api);
    }

    @Provides
    @Singleton
    public static ArchiveSenderSource provideArchiveSenderSource(
            ConversationRepository conversationRepository) {
        return new ConversationArchiveSenderSource(conversationRepository);
    }
}

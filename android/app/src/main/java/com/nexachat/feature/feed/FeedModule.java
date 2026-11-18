package com.nexachat.feature.feed;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;
import retrofit2.Retrofit;

/** Hilt bindings for the public feed client, repository and the first-use notice flag. */
@Module
@InstallIn(SingletonComponent.class)
public abstract class FeedModule {

    @Binds
    @Singleton
    public abstract FeedRepository bindFeedRepository(RetrofitFeedRepository repository);

    @Binds
    @Singleton
    public abstract FeedNoticeStore bindFeedNoticeStore(PrefsFeedNoticeStore store);

    @Provides
    @Singleton
    public static FeedApi provideFeedApi(Retrofit retrofit) {
        return retrofit.create(FeedApi.class);
    }
}

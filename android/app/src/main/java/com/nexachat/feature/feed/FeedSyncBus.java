package com.nexachat.feature.feed;

import androidx.annotation.NonNull;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * In-memory event bus syncing feed mutations across {@link PublicFeedFragment},
 * {@link PostDetailFragment}, and {@link PostComposeFragment}.
 */
@Singleton
public final class FeedSyncBus {

    public sealed interface Event permits PostCreated, PostUpdated, PostDeleted { }

    public record PostCreated(@NonNull FeedRepository.Post post) implements Event { }

    public record PostUpdated(@NonNull FeedRepository.Post post) implements Event { }

    public record PostDeleted(@NonNull String postId) implements Event { }

    private final PublishSubject<Event> events = PublishSubject.create();

    @Inject
    public FeedSyncBus() { }

    public void publish(@NonNull Event event) {
        events.onNext(event);
    }

    @NonNull
    public Observable<Event> observe() {
        return events.hide();
    }
}

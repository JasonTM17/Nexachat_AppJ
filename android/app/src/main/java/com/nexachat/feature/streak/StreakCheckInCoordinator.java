package com.nexachat.feature.streak;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.nexachat.core.network.ApiCalls;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.storage.TokenStore;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.Objects;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Sends the daily streak check-in when the app comes to the foreground signed in. The
 * repository applies the once-per-streak-day rule; this class only gates on the session and
 * keeps a single request in flight. Failures are silent and retried on the next foreground.
 */
@Singleton
public final class StreakCheckInCoordinator {

    private final StreakRepository repository;
    private final Supplier<Single<Boolean>> signedIn;
    private final StreakStore store;
    private final MilestoneTracker milestoneTracker;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private ApiCalls.Cancellable inFlight;

    @Inject
    public StreakCheckInCoordinator(
            @NonNull StreakRepository repository,
            @NonNull TokenStore tokenStore,
            @NonNull StreakStore store,
            @NonNull MilestoneTracker milestoneTracker,
            @NonNull SessionEvents sessionEvents) {
        this(repository, tokenStore::hasStoredRefreshCredential, store, milestoneTracker);
        disposables.add(sessionEvents.getEvents().subscribe(event -> {
            if (event instanceof SessionEvents.Event.LoggedOut) {
                onSessionEnded();
            }
        }, ignored -> { }));
    }

    @VisibleForTesting
    public StreakCheckInCoordinator(
            @NonNull StreakRepository repository,
            @NonNull Supplier<Single<Boolean>> signedIn,
            @NonNull StreakStore store,
            @NonNull MilestoneTracker milestoneTracker) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.signedIn = Objects.requireNonNull(signedIn, "signedIn");
        this.store = Objects.requireNonNull(store, "store");
        this.milestoneTracker = Objects.requireNonNull(milestoneTracker, "milestoneTracker");
    }

    /** Call when the app reaches the foreground and whenever a signed-in home screen opens. */
    public void onAppForeground() {
        disposables.add(signedIn.get().subscribe(isSignedIn -> {
            if (Boolean.TRUE.equals(isSignedIn)) {
                send();
            }
        }, ignored -> { }));
    }

    private synchronized void send() {
        if (inFlight != null || !repository.isCheckInDue()) {
            return;
        }
        final boolean[] finished = {false};
        ApiCalls.Cancellable call = repository.checkIn(new StreakRepository.Callback<>() {
            @Override
            public void onSuccess(@NonNull StreakDto value) {
                finish();
            }

            @Override
            public void onFailure() {
                finish();
            }

            private void finish() {
                synchronized (StreakCheckInCoordinator.this) {
                    finished[0] = true;
                    inFlight = null;
                }
            }
        });
        if (!finished[0]) {
            inFlight = call;
        }
    }

    private synchronized void onSessionEnded() {
        if (inFlight != null) {
            inFlight.cancel();
            inFlight = null;
        }
        store.clear();
        milestoneTracker.clear();
    }
}

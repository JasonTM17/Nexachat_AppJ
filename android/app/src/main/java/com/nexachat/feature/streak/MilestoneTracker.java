package com.nexachat.feature.streak;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Decides which milestone celebration to show. A milestone is celebrated once per chain: the
 * key is {@code scope|startedOn|milestone}. Candidates queue while another celebration is on
 * screen or until the screen asks; {@link #next()} hands out the highest one. Its key is
 * persisted only by {@link #shown(Celebration)}, once the sheet is really on screen, so a
 * celebration that could not be shown stays eligible. Main-thread only.
 */
@Singleton
public final class MilestoneTracker {

    /** One streak milestone ready to be shown. */
    public static final class Celebration {
        @NonNull private final String scope;
        @Nullable private final String startedOn;
        private final int milestone;
        private final int count;

        public Celebration(
                @NonNull String scope, @Nullable String startedOn, int milestone, int count) {
            this.scope = scope;
            this.startedOn = startedOn;
            this.milestone = milestone;
            this.count = count;
        }

        @NonNull public String getScope() { return scope; }

        @Nullable public String getStartedOn() { return startedOn; }

        public int getMilestone() { return milestone; }

        public int getCount() { return count; }

        @NonNull
        public String key() {
            return StreakRules.celebrationKey(scope, startedOn, milestone);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Celebration that && key().equals(that.key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }
    }

    /** Scope used for the app-wide check-in streak. */
    public static final String SCOPE_CHECK_IN = "checkin";

    private final StreakStore store;
    private final List<Celebration> pending = new ArrayList<>();
    @Nullable private Celebration handedOut;
    private boolean showing;

    @Inject
    public MilestoneTracker(@NonNull StreakStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Queues the fetched streak when it reached a milestone that was not celebrated yet.
     * Streaks the user is not viewing must not be offered.
     */
    public void offer(@NonNull String scope, @NonNull StreakDto streak) {
        int milestone = streak.getMilestone();
        if (!StreakRules.isMilestone(milestone) || streak.getCount() < milestone) {
            return;
        }
        Celebration candidate = new Celebration(
                scope, streak.getStartedOn(), milestone, streak.getCount());
        if (store.isCelebrated(candidate.key()) || pending.contains(candidate)) {
            return;
        }
        pending.add(candidate);
    }

    /**
     * The highest queued celebration, or null when none is due or one is already showing. It
     * counts as showing until {@link #shown(Celebration)} followed by {@link #dismissed()}, or
     * until {@link #notShown(Celebration)} hands it back. Nothing is persisted here.
     */
    @Nullable
    public Celebration next() {
        if (showing || pending.isEmpty()) {
            return null;
        }
        Celebration best = pending.get(0);
        for (Celebration candidate : pending) {
            if (candidate.getMilestone() > best.getMilestone()) {
                best = candidate;
            }
        }
        pending.remove(best);
        handedOut = best;
        showing = true;
        return best;
    }

    /** The handed-out celebration is on screen: it is celebrated for good and never offered again. */
    public void shown(@NonNull Celebration celebration) {
        store.markCelebrated(celebration.key());
        if (celebration.equals(handedOut)) {
            handedOut = null;
        }
    }

    /**
     * The handed-out celebration could not be put on screen. It returns to the queue unpersisted
     * and nothing is showing, so the next trigger offers it again.
     */
    public void notShown(@NonNull Celebration celebration) {
        showing = false;
        if (celebration.equals(handedOut)) {
            handedOut = null;
            if (!store.isCelebrated(celebration.key()) && !pending.contains(celebration)) {
                pending.add(celebration);
            }
        }
    }

    /** The sheet went away; the next queued celebration may show. */
    public void dismissed() {
        showing = false;
    }

    /** Drops queued candidates of a screen the user left. */
    public void discardPending(@NonNull String scope) {
        pending.removeIf(candidate -> candidate.getScope().equals(scope));
        if (handedOut != null && handedOut.getScope().equals(scope)) {
            handedOut = null;
        }
    }

    /** Resets in-memory state at the end of a session. */
    public void clear() {
        pending.clear();
        handedOut = null;
        showing = false;
    }
}

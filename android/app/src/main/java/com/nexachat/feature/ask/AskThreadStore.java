package com.nexachat.feature.ask;

import androidx.annotation.Nullable;
import com.nexachat.core.network.SessionEvents;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Session-scoped, in-memory retention of ask sheet threads keyed by conversation id.
 * Closing the sheet snapshots its thread here so reopening the sheet in the same session
 * restores the earlier questions and answers instead of starting blank. Entries are
 * memory-only: process death drops them, and any session boundary (logout, re-login,
 * reauthentication) wipes everything so one account never sees another account's thread.
 */
@Singleton
public final class AskThreadStore {
    private static final int MAX_RETAINED_THREADS = 20;

    private final Map<String, Snapshot> threads = new LinkedHashMap<>(16, 0.75f, false);
    // The store is a process singleton, matching the lifetime of these subscriptions.
    private final CompositeDisposable sessionSubscriptions = new CompositeDisposable();

    @Inject
    public AskThreadStore(SessionEvents sessionEvents) {
        Objects.requireNonNull(sessionEvents, "sessionEvents");
        sessionSubscriptions.add(sessionEvents.getEvents().subscribe(this::onSessionEvent));
        sessionSubscriptions.add(sessionEvents.getLifecycleEvents().subscribe(this::onSessionEvent));
    }

    private void onSessionEvent(SessionEvents.Event event) {
        if (event instanceof SessionEvents.Event.SessionClosing
                || event instanceof SessionEvents.Event.LoggedOut
                || event instanceof SessionEvents.Event.ReauthenticationRequired
                || event instanceof SessionEvents.Event.SessionAvailable) {
            synchronized (this) {
                threads.clear();
            }
        }
    }

    /**
     * Retains the thread for one conversation. A blank draft with no turns removes the
     * entry instead, so a fresh conversation never restores an empty snapshot.
     */
    public void save(
            @Nullable String conversationId,
            @Nullable String draft,
            @Nullable List<AskTurn> turns) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        String safeDraft = draft == null ? "" : draft;
        List<AskTurn> safeTurns = turns == null || turns.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(turns));
        synchronized (this) {
            if (safeTurns.isEmpty() && safeDraft.trim().isEmpty()) {
                threads.remove(conversationId);
                return;
            }
            threads.remove(conversationId);
            threads.put(conversationId, new Snapshot(safeDraft, safeTurns));
            while (threads.size() > MAX_RETAINED_THREADS) {
                String eldest = threads.keySet().iterator().next();
                threads.remove(eldest);
            }
        }
    }

    /** Returns the retained thread for one conversation, or null when nothing was kept. */
    @Nullable
    public synchronized Snapshot restore(@Nullable String conversationId) {
        if (conversationId == null) {
            return null;
        }
        return threads.get(conversationId);
    }

    /** Immutable retained thread: the unanswered draft plus the completed turns. */
    public static final class Snapshot {
        private final String draft;
        private final List<AskTurn> turns;

        private Snapshot(String draft, List<AskTurn> turns) {
            this.draft = draft;
            this.turns = turns;
        }

        public String getDraft() {
            return draft;
        }

        public List<AskTurn> getTurns() {
            return turns;
        }
    }
}

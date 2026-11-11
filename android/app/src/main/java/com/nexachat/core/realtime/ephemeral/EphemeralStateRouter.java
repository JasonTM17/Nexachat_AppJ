package com.nexachat.core.realtime.ephemeral;

import androidx.annotation.NonNull;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.data.TypingEvent;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Selects one ephemeral source while leaving the durable STOMP stream active.
 * Firebase remains an opt-in source and STOMP is always the initial fallback.
 */
public final class EphemeralStateRouter implements AutoCloseable {
    private final TokenRefresher tokenRefresher;
    private final EphemeralStateSource firebase;
    private final StompEphemeralStateSource stomp;
    private final boolean firebaseEnabled;
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private final BehaviorProcessor<EphemeralTransport> selected =
            BehaviorProcessor.createDefault(EphemeralTransport.STOMP);
    private final PublishProcessor<PresenceUpdate> presenceEvents = PublishProcessor.create();
    private final PublishProcessor<TypingEvent> typingEvents = PublishProcessor.create();

    private EphemeralScope scope = EphemeralScope.empty();
    private boolean foreground;
    private boolean firebaseRequested;
    private long activeSessionEpoch = Long.MIN_VALUE;
    private long firebaseGeneration;

    public EphemeralStateRouter(
            @NonNull TokenRefresher tokenRefresher,
            @NonNull SessionEvents sessionEvents,
            @NonNull StompEphemeralStateSource stomp,
            @NonNull EphemeralStateSource firebase,
            boolean firebaseEnabled) {
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
        this.firebase = Objects.requireNonNull(firebase, "firebase");
        this.stomp = Objects.requireNonNull(stomp, "stomp");
        this.firebaseEnabled = firebaseEnabled;

        subscriptions.add(stomp.presenceUpdates().subscribe(this::onStompPresence));
        subscriptions.add(stomp.typingUpdates().subscribe(this::onStompTyping));
        subscriptions.add(firebase.statusUpdates().subscribe(this::onFirebaseStatus));
        subscriptions.add(firebase.presenceUpdates().subscribe(this::onFirebasePresence));
        subscriptions.add(firebase.typingUpdates().subscribe(this::onFirebaseTyping));
        subscriptions.add(sessionEvents.getEvents().subscribe(this::onSessionEvent));
        subscriptions.add(sessionEvents.getLifecycleEvents().subscribe(this::onSessionEvent));
    }

    @NonNull
    public Flowable<PresenceUpdate> presenceEvents() {
        return presenceEvents.hide().onBackpressureBuffer(256, () -> { },
                io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    @NonNull
    public Flowable<TypingEvent> typingEvents() {
        return typingEvents.hide().onBackpressureBuffer(256, () -> { },
                io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    @NonNull
    public Flowable<EphemeralTransport> selectionChanges() {
        return selected.hide().distinctUntilChanged();
    }

    public boolean isFirebaseSelected() {
        return selected.getValue() == EphemeralTransport.FIREBASE;
    }

    public synchronized void onAppForeground() {
        foreground = true;
        long epoch = tokenRefresher.sessionEpoch();
        if (tokenRefresher.isSessionCurrent(epoch)) {
            activate(epoch);
        }
    }

    public synchronized void onAppBackground() {
        foreground = false;
        if (scope.getTypingConversationId() != null) {
            firebase.clearTyping(scope.getTypingConversationId());
        }
        stopFirebase();
        activeSessionEpoch = Long.MIN_VALUE;
        setSelected(EphemeralTransport.STOMP);
    }

    /** Direct inbox presence scopes are derived from the authenticated inbox response. */
    public synchronized void setPresenceObservers(@NonNull Map<String, String> conversationByPeer) {
        EphemeralScope next = scope.withPresencePeers(conversationByPeer);
        setScope(next);
    }

    /** Only the currently open conversation and its authoritative roster are observed for typing. */
    public synchronized void setTypingScope(
            @NonNull String conversationId, @NonNull Set<String> memberIds) {
        setScope(scope.withTypingConversation(conversationId, memberIds));
    }

    public synchronized void clearTypingScope(@NonNull String conversationId) {
        firebase.clearTyping(conversationId);
        setScope(scope.withoutTypingConversation(conversationId));
    }

    public synchronized void clearTyping(@NonNull String conversationId) {
        firebase.clearTyping(conversationId);
    }

    public synchronized boolean sendTyping(@NonNull String conversationId) {
        if (isFirebaseSelected()) {
            if (firebase.sendTyping(conversationId)) return true;
            setSelected(EphemeralTransport.STOMP);
            firebase.stop();
            firebaseRequested = false;
        }
        return stomp.sendTyping(conversationId);
    }

    private synchronized void setScope(EphemeralScope next) {
        if (scope.equals(next)) return;
        scope = next;
        if (!foreground || !firebaseEnabled || !isCurrentSession()) return;
        if (!firebaseRequested) {
            activate(activeSessionEpoch);
            return;
        }
        long generation = firebaseGeneration;
        setSelected(EphemeralTransport.STOMP);
        firebase.updateScope(activeSessionEpoch, generation, scope);
    }

    private void onSessionEvent(SessionEvents.Event event) {
        synchronized (this) {
            if (event instanceof SessionEvents.Event.SessionClosing
                    || event instanceof SessionEvents.Event.LoggedOut
                    || event instanceof SessionEvents.Event.ReauthenticationRequired) {
                scope = EphemeralScope.empty();
                stopFirebase();
                activeSessionEpoch = Long.MIN_VALUE;
                setSelected(EphemeralTransport.STOMP);
                return;
            }
            if (event instanceof SessionEvents.Event.SessionAvailable) {
                long epoch = tokenRefresher.sessionEpoch();
                if (!tokenRefresher.isSessionCurrent(epoch)) return;
                if (activeSessionEpoch != epoch) scope = EphemeralScope.empty();
                if (foreground) activate(epoch);
                return;
            }
            if (event instanceof SessionEvents.Event.SessionRefreshed) {
                long epoch = tokenRefresher.sessionEpoch();
                if (foreground && tokenRefresher.isSessionCurrent(epoch)) {
                    stopFirebase();
                    activeSessionEpoch = Long.MIN_VALUE;
                    activate(epoch);
                }
            }
        }
    }

    private synchronized void activate(long epoch) {
        if (!firebaseEnabled || !foreground || !tokenRefresher.isSessionCurrent(epoch)) {
            setSelected(EphemeralTransport.STOMP);
            return;
        }
        if (firebaseRequested && activeSessionEpoch == epoch) return;
        firebase.stop();
        activeSessionEpoch = epoch;
        firebaseGeneration++;
        firebaseRequested = true;
        setSelected(EphemeralTransport.STOMP);
        firebase.start(epoch, firebaseGeneration, scope);
    }

    private synchronized void onFirebaseStatus(EphemeralStateSource.SourceStatus status) {
        if (!isCurrentFirebase(status.sessionEpoch(), status.generation())) return;
        if (status instanceof EphemeralStateSource.SourceStatus.Ready) {
            setSelected(EphemeralTransport.FIREBASE);
        } else if (status instanceof EphemeralStateSource.SourceStatus.Starting) {
            setSelected(EphemeralTransport.STOMP);
        } else if (status instanceof EphemeralStateSource.SourceStatus.Unavailable) {
            firebaseRequested = false;
            setSelected(EphemeralTransport.STOMP);
            firebase.stop();
        }
    }

    private synchronized void onStompPresence(EphemeralStateSource.Stamped<PresenceUpdate> event) {
        tokenRefresher.runIfSessionCurrent(event.sessionEpoch(), () -> {
            synchronized (this) {
                if (selected.getValue() == EphemeralTransport.STOMP
                        && event.generation() == 0L
                        && isCurrentEpoch(event.sessionEpoch())) {
                    presenceEvents.onNext(event.value());
                }
            }
        });
    }

    private synchronized void onStompTyping(EphemeralStateSource.Stamped<TypingEvent> event) {
        tokenRefresher.runIfSessionCurrent(event.sessionEpoch(), () -> {
            synchronized (this) {
                if (selected.getValue() == EphemeralTransport.STOMP
                        && event.generation() == 0L
                        && isCurrentEpoch(event.sessionEpoch())) {
                    typingEvents.onNext(event.value());
                }
            }
        });
    }

    private synchronized void onFirebasePresence(
            EphemeralStateSource.Stamped<PresenceUpdate> event) {
        tokenRefresher.runIfSessionCurrent(event.sessionEpoch(), () -> {
            synchronized (this) {
                if (selected.getValue() == EphemeralTransport.FIREBASE
                        && isCurrentFirebase(event.sessionEpoch(), event.generation())) {
                    presenceEvents.onNext(event.value());
                }
            }
        });
    }

    private synchronized void onFirebaseTyping(
            EphemeralStateSource.Stamped<TypingEvent> event) {
        tokenRefresher.runIfSessionCurrent(event.sessionEpoch(), () -> {
            synchronized (this) {
                if (selected.getValue() == EphemeralTransport.FIREBASE
                        && isCurrentFirebase(event.sessionEpoch(), event.generation())) {
                    typingEvents.onNext(event.value());
                }
            }
        });
    }

    private boolean isCurrentFirebase(long epoch, long generation) {
        return firebaseRequested && foreground && activeSessionEpoch == epoch
                && firebaseGeneration == generation && isCurrentEpoch(epoch);
    }

    private boolean isCurrentSession() {
        return activeSessionEpoch != Long.MIN_VALUE && isCurrentEpoch(activeSessionEpoch);
    }

    private boolean isCurrentEpoch(long epoch) {
        return tokenRefresher.isSessionCurrent(epoch);
    }

    private void stopFirebase() {
        firebaseRequested = false;
        firebaseGeneration++;
        firebase.stop();
    }

    private void setSelected(EphemeralTransport next) {
        if (selected.getValue() != next) selected.onNext(next);
    }

    @Override
    public synchronized void close() {
        onAppBackground();
        subscriptions.clear();
        presenceEvents.onComplete();
        typingEvents.onComplete();
    }
}

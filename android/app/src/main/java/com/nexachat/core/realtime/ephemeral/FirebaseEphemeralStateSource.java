package com.nexachat.core.realtime.ephemeral;

import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.OnDisconnect;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.FirebaseRealtimeApi;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.FirebaseBridgeResponse;
import com.nexachat.core.network.dto.FirebaseLeaseRequest;
import com.nexachat.core.network.dto.FirebaseLeaseResponse;
import com.nexachat.data.PeerPresence;
import com.nexachat.data.TypingEvent;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.subjects.SingleSubject;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Firebase RTDB implementation for foreground-only presence and typing. */
@Singleton
public final class FirebaseEphemeralStateSource implements EphemeralStateSource {
    private static final long RENEW_INTERVAL_SECONDS = 60L;
    private static final long CONNECT_TIMEOUT_SECONDS = 15L;

    private final Context context;
    private final FirebaseRealtimeApi api;
    private final TokenRefresher tokenRefresher;
    private final Object lock = new Object();
    private final BehaviorProcessor<SourceStatus> statuses = BehaviorProcessor.create();
    private final PublishProcessor<Stamped<PresenceUpdate>> presenceEvents = PublishProcessor.create();
    private final PublishProcessor<Stamped<TypingEvent>> typingEvents = PublishProcessor.create();
    private final BehaviorProcessor<Boolean> connectionEvents =
            BehaviorProcessor.createDefault(Boolean.FALSE);
    private final Map<String, ValueEventListener> presenceListeners = new LinkedHashMap<>();
    private final Map<String, ValueEventListener> typingListeners = new LinkedHashMap<>();
    private final Map<String, PeerPresence> currentPresence = new LinkedHashMap<>();
    private final Map<String, TypingEvent> currentTyping = new LinkedHashMap<>();
    private final Set<FirebaseDatabase> persistenceConfigured =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private final FirebaseAuthQueue authQueue = new FirebaseAuthQueue();
    @Nullable private FirebaseApp firebaseApp;
    @Nullable private FirebaseAuth firebaseAuth;
    @Nullable private FirebaseDatabase database;
    @Nullable private DatabaseReference ownPresenceRef;
    @Nullable private DatabaseReference ownTypingRef;
    @Nullable private OnDisconnect ownPresenceDisconnect;
    @Nullable private OnDisconnect ownTypingDisconnect;
    @Nullable private ValueEventListener connectionListener;
    @Nullable private ValueEventListener serverOffsetListener;
    @Nullable private Disposable leaseRenewal;
    @Nullable private Disposable scopeUpdate;
    @Nullable private String bridgeId;
    @Nullable private String userId;
    private EphemeralScope scope = EphemeralScope.empty();
    private long leaseUntilEpochMs;
    private volatile long serverOffsetEpochMs;
    private long sessionEpoch = Long.MIN_VALUE;
    private long generation;
    private long scopeRevision;
    private long configuredScopeRevision = Long.MIN_VALUE;
    private long lastTypingElapsedMs = -1L;
    private boolean active;
    private boolean bootstrapping;
    private boolean connected;
    private boolean ready;

    @Inject
    public FirebaseEphemeralStateSource(
            @dagger.hilt.android.qualifiers.ApplicationContext @NonNull Context context,
            @NonNull FirebaseRealtimeApi api,
            @NonNull TokenRefresher tokenRefresher) {
        this.context = context.getApplicationContext();
        this.api = api;
        this.tokenRefresher = tokenRefresher;
    }

    @NonNull @Override
    public io.reactivex.rxjava3.core.Flowable<Stamped<PresenceUpdate>> presenceUpdates() {
        return presenceEvents.onBackpressureBuffer(256, () -> { },
                io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    @NonNull @Override
    public io.reactivex.rxjava3.core.Flowable<Stamped<TypingEvent>> typingUpdates() {
        return typingEvents.onBackpressureBuffer(256, () -> { },
                io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    @NonNull @Override
    public io.reactivex.rxjava3.core.Flowable<SourceStatus> statusUpdates() {
        return statuses.hide();
    }

    @Override
    public void start(long expectedEpoch, long expectedGeneration, @NonNull EphemeralScope nextScope) {
        stop();
        synchronized (lock) {
            active = true;
            bootstrapping = true;
            ready = false;
            connected = false;
            sessionEpoch = expectedEpoch;
            generation = expectedGeneration;
            scopeRevision++;
            scope = nextScope;
            currentPresence.clear();
            currentTyping.clear();
            lastTypingElapsedMs = -1L;
        }
        statuses.onNext(new SourceStatus.Starting(expectedEpoch, expectedGeneration));
        if (!isCurrent(expectedEpoch, expectedGeneration)) return;
        if (!hasDefaultFirebaseApp()) {
            markUnavailable(expectedEpoch, expectedGeneration);
            return;
        }

        requiredBody(api.issueBridge())
                .subscribeOn(Schedulers.io())
                .flatMap(bridge -> bootstrap(bridge, expectedEpoch, expectedGeneration))
                .subscribe(ignored -> { }, ignored -> markUnavailable(expectedEpoch, expectedGeneration));
    }

    private Single<Boolean> bootstrap(
            FirebaseBridgeResponse bridge, long expectedEpoch, long expectedGeneration) {
        String token = bridge.getCustomToken();
        String url = bridge.getDatabaseUrl();
        String nextBridgeId = bridge.getBridgeId();
        if (!isCurrent(expectedEpoch, expectedGeneration)) {
            revokeBestEffort(nextBridgeId);
            return Single.just(Boolean.FALSE);
        }
        if (!"FIREBASE".equals(bridge.getTransport())
                || token == null || token.isBlank()
                || !EphemeralScope.safePath(nextBridgeId)
                || !validDatabaseUrl(url)) {
            revokeBestEffort(nextBridgeId);
            return Single.error(new IllegalStateException("invalid Firebase bridge response"));
        }

        try {
            FirebaseRuntime runtime = initializeRuntime(url, expectedEpoch, expectedGeneration);
            boolean current;
            synchronized (lock) {
                current = isCurrentLocked(expectedEpoch, expectedGeneration);
                if (current) {
                    bridgeId = nextBridgeId;
                    userId = null;
                    database = runtime.database;
                    firebaseApp = runtime.app;
                    firebaseAuth = runtime.auth;
                }
            }
            if (!current) {
                revokeBestEffort(nextBridgeId);
                disposeUnadmittedRuntime(runtime);
                return Single.just(Boolean.FALSE);
            }

            Completable signIn = authQueue.enqueueSessionOperation(
                    tokenRefresher, expectedEpoch,
                    () -> isCurrent(expectedEpoch, expectedGeneration),
                    Completable.fromAction(runtime.auth::signOut)
                    .andThen(Completable.defer(() -> taskSingle(
                            runtime.auth.signInWithCustomToken(token)).ignoreElement())),
                    () -> Completable.fromAction(runtime.auth::signOut));
            Completable setup = signIn
                    .andThen(waitForConnection(runtime.database, expectedEpoch, expectedGeneration))
                    .andThen(waitForServerOffset(runtime.database, expectedEpoch, expectedGeneration))
                    .andThen(configureLatestScope(expectedEpoch, expectedGeneration))
                    .andThen(startLeaseRenewal(expectedEpoch, expectedGeneration));
            return setup.andThen(Single.just(Boolean.TRUE));
        } catch (RuntimeException failure) {
            revokeBestEffort(nextBridgeId);
            return Single.error(failure);
        }
    }

    @Override
    public void updateScope(long expectedEpoch, long expectedGeneration, @NonNull EphemeralScope nextScope) {
        final long revision;
        final String previousTypingConversation;
        final FirebaseDatabase currentDatabase;
        final boolean configureNow;
        final Disposable previousUpdate;
        synchronized (lock) {
            if (!isCurrentLocked(expectedEpoch, expectedGeneration)) return;
            previousTypingConversation = scope.getTypingConversationId();
            scope = nextScope;
            revision = ++scopeRevision;
            ready = false;
            currentDatabase = database;
            configureNow = !bootstrapping;
            previousUpdate = configureNow ? scopeUpdate : null;
            if (configureNow) scopeUpdate = null;
            removeScopeListenersLocked();
            currentPresence.clear();
            currentTyping.clear();
        }
        if (previousTypingConversation != null
                && !Objects.equals(previousTypingConversation, nextScope.getTypingConversationId())) {
            clearTyping(previousTypingConversation);
        }
        if (currentDatabase != null) currentDatabase.purgeOutstandingWrites();
        statuses.onNext(new SourceStatus.Starting(expectedEpoch, expectedGeneration));
        if (previousUpdate != null) previousUpdate.dispose();
        if (configureNow) {
            Disposable nextUpdate = configureLatestScope(expectedEpoch, expectedGeneration)
                    .subscribeOn(Schedulers.io())
                    .subscribe(() -> { }, ignored -> {
                        if (isCurrentSessionGeneration(expectedEpoch, expectedGeneration)) {
                            markUnavailable(expectedEpoch, expectedGeneration);
                        }
                    });
            synchronized (lock) {
                if (isCurrentLocked(expectedEpoch, expectedGeneration)
                        && scopeRevision == revision) {
                    scopeUpdate = nextUpdate;
                } else {
                    nextUpdate.dispose();
                }
            }
        }
    }

    @Override
    public void stop() {
        final String bridge;
        final FirebaseDatabase currentDatabase;
        final FirebaseApp currentApp;
        final FirebaseAuth currentAuth;
        final FirebaseUser currentUser;
        final DatabaseReference presenceRef;
        final DatabaseReference typingRef;
        final String oldUserId;
        final boolean wasConnected;
        final Map<String, Object> offlinePayload;
        synchronized (lock) {
            oldUserId = userId;
            wasConnected = connected;
            offlinePayload = offlinePresencePayload();
            active = false;
            bootstrapping = false;
            ready = false;
            connected = false;
            bridge = bridgeId;
            currentDatabase = database;
            currentApp = firebaseApp;
            currentAuth = firebaseAuth;
            currentUser = currentAuth == null ? null : currentAuth.getCurrentUser();
            presenceRef = ownPresenceRef;
            typingRef = ownTypingRef;
            removeListenersLocked();
            if (leaseRenewal != null) {
                leaseRenewal.dispose();
                leaseRenewal = null;
            }
            if (scopeUpdate != null) {
                scopeUpdate.dispose();
                scopeUpdate = null;
            }
            bridgeId = null;
            userId = null;
            database = null;
            firebaseApp = null;
            firebaseAuth = null;
            ownPresenceRef = null;
            ownTypingRef = null;
            ownPresenceDisconnect = null;
            ownTypingDisconnect = null;
            currentPresence.clear();
            currentTyping.clear();
        }
        Completable cleanup = Completable.complete();
        if (currentDatabase != null) {
            if (wasConnected && currentUser != null && currentUser.getUid().equals(oldUserId)) {
                if (typingRef != null) cleanup = cleanup.andThen(taskCompletable(typingRef.removeValue()));
                if (presenceRef != null) cleanup = cleanup.andThen(taskCompletable(
                        presenceRef.setValue(offlinePayload)));
            }
            cleanup = cleanup.timeout(2L, TimeUnit.SECONDS, Schedulers.io())
                    .onErrorComplete()
                    .andThen(Completable.fromAction(() -> {
                        currentDatabase.purgeOutstandingWrites();
                        currentDatabase.goOffline();
                    }));
        }
        if (bridge != null && !bridge.isBlank()) revokeBestEffort(bridge);
        if (currentAuth != null) cleanup = cleanup.andThen(Completable.fromAction(currentAuth::signOut));
        if (currentApp != null) cleanup = cleanup.doFinally(currentApp::delete);
        if (currentDatabase != null || currentAuth != null || currentApp != null) {
            authQueue.enqueue(cleanup).subscribeOn(Schedulers.io())
                    .subscribe(() -> { }, ignored -> { });
        }
        // A stop caused by the router is not itself a transport failure.
    }

    @Override
    public boolean sendTyping(@NonNull String conversationId) {
        final DatabaseReference reference;
        final long now;
        final long expectedEpoch;
        final long expectedGeneration;
        final String currentBridge;
        synchronized (lock) {
            if (!active || !ready || !connected || database == null || userId == null
                    || !Objects.equals(scope.getTypingConversationId(), conversationId)
                    || !isCurrentLocked(sessionEpoch, generation)) {
                return false;
            }
            long elapsed = SystemClock.elapsedRealtime();
            if (!EphemeralTimeRules.canSendTyping(lastTypingElapsedMs, elapsed)) return true;
            lastTypingElapsedMs = elapsed;
            now = System.currentTimeMillis() + serverOffsetEpochMs;
            reference = database.getReference("typing").child(conversationId).child(userId);
            expectedEpoch = sessionEpoch;
            expectedGeneration = generation;
            currentBridge = bridgeId;
        }
        Map<String, Object> value = new HashMap<>();
        value.put("bridgeId", currentBridge);
        value.put("updatedAt", ServerValue.TIMESTAMP);
        value.put("expiresAt", now + EphemeralTimeRules.TYPING_TTL_MS);
        reference.setValue(value).addOnFailureListener(
                ignored -> markUnavailable(expectedEpoch, expectedGeneration));
        return true;
    }

    @Override
    public void clearTyping(@NonNull String conversationId) {
        DatabaseReference reference;
        synchronized (lock) {
            if (!active || !connected || database == null || userId == null) return;
            reference = database.getReference("typing").child(conversationId).child(userId);
        }
        reference.removeValue();
    }

    private Single<FirebaseLeaseResponse> renewLease(
            EphemeralScope requestedScope, long expectedEpoch, long expectedGeneration) {
        String currentBridge;
        synchronized (lock) {
            if (!isCurrentLocked(expectedEpoch, expectedGeneration) || bridgeId == null) {
                return Single.error(new IllegalStateException("stale Firebase bridge"));
            }
            currentBridge = bridgeId;
        }
        return requiredBody(api.renewLease(new FirebaseLeaseRequest(
                        currentBridge, requestedScope.leasedConversationIds())))
                .map(response -> {
                    if (!"FIREBASE".equals(response.getTransport())
                            || !validLeaseUntil(response.getLeaseUntil())) {
                        throw new IllegalStateException("invalid Firebase lease response");
                    }
                    return response;
                });
    }

    private Completable activateLease(
            FirebaseLeaseResponse lease,
            EphemeralScope requestedScope,
            long expectedEpoch,
            long expectedGeneration) {
        DatabaseReference presenceRef;
        String uid;
        synchronized (lock) {
            if (!isCurrentLocked(expectedEpoch, expectedGeneration)) {
                return Completable.error(new IllegalStateException("stale Firebase bridge"));
            }
            FirebaseUser currentUser = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
            if (currentUser == null) return Completable.error(new IllegalStateException("Firebase user unavailable"));
            uid = currentUser.getUid();
            userId = uid;
            leaseUntilEpochMs = leaseUntilEpochMs(lease);
            presenceRef = database.getReference("presence").child(uid).child(bridgeId);
            ownPresenceRef = presenceRef;
        }
        Map<String, Object> offline = offlinePresencePayload();
        Map<String, Object> online = new HashMap<>();
        online.put("online", Boolean.TRUE);
        online.put("lastSeen", 0L);
        online.put("updatedAt", ServerValue.TIMESTAMP);
        online.put("leaseUntil", leaseUntilEpochMs(lease));
        return taskCompletable(presenceRef.onDisconnect().setValue(offline))
                .andThen(Completable.fromAction(() -> {
                    synchronized (lock) {
                        ownPresenceDisconnect = presenceRef.onDisconnect();
                    }
                }))
                .andThen(taskCompletable(presenceRef.setValue(online)))
                .andThen(registerTypingDisconnect(requestedScope, uid, expectedEpoch, expectedGeneration));
    }

    private Completable registerTypingDisconnect(
            EphemeralScope requestedScope, String uid, long expectedEpoch, long expectedGeneration) {
        String conversationId = requestedScope.getTypingConversationId();
        DatabaseReference nextRef = conversationId == null
                ? null : database.getReference("typing").child(conversationId).child(uid);
        Completable cancelOld;
        DatabaseReference oldRef;
        synchronized (lock) {
            oldRef = ownTypingRef;
            if (oldRef != null && !oldRef.equals(nextRef)) {
                cancelOld = taskCompletable(oldRef.onDisconnect().cancel())
                        .andThen(taskCompletable(oldRef.removeValue()));
            } else {
                cancelOld = Completable.complete();
            }
        }
        if (nextRef == null) {
            return cancelOld.andThen(Completable.fromAction(() -> {
                synchronized (lock) {
                    ownTypingRef = null;
                    ownTypingDisconnect = null;
                }
            }));
        }
        OnDisconnect disconnect = nextRef.onDisconnect();
        return cancelOld.andThen(taskCompletable(disconnect.removeValue()))
                .andThen(Completable.fromAction(() -> {
                    synchronized (lock) {
                        if (isCurrentLocked(expectedEpoch, expectedGeneration)) {
                            ownTypingRef = nextRef;
                            ownTypingDisconnect = disconnect;
                        }
                    }
                }));
    }

    private Completable installScope(
            EphemeralScope requestedScope, long expectedEpoch, long expectedGeneration) {
        return installScope(requestedScope, expectedEpoch, expectedGeneration, currentScopeRevision());
    }

    private Completable installScope(
            EphemeralScope requestedScope,
            long expectedEpoch,
            long expectedGeneration,
            long requestedRevision) {
        return Completable.create(emitter -> {
            final List<ListenerTarget> targets = new ArrayList<>();
            final int expectedCount;
            synchronized (lock) {
                if (!isCurrentLocked(expectedEpoch, expectedGeneration)
                        || scopeRevision != requestedRevision) {
                    emitter.onError(new IllegalStateException("stale Firebase scope"));
                    return;
                }
                removeScopeListenersLocked();
                currentPresence.clear();
                currentTyping.clear();
                for (String user : requestedScope.getPresenceConversationByUser().keySet()) {
                    targets.add(new ListenerTarget(
                            "presence:" + user,
                            database.getReference("presence").child(user),
                            TargetKind.PRESENCE,
                            user,
                            null));
                }
                String typingConversation = requestedScope.getTypingConversationId();
                if (typingConversation != null) {
                    String self = userId;
                    for (String member : requestedScope.getTypingUserIds()) {
                        if (member.equals(self)) continue;
                        String key = typingKey(typingConversation, member);
                        targets.add(new ListenerTarget(
                                key,
                                database.getReference("typing").child(typingConversation).child(member),
                                TargetKind.TYPING,
                                member,
                                typingConversation));
                    }
                }
                expectedCount = targets.size();
            }
            if (expectedCount == 0) {
                emitter.onComplete();
                return;
            }

            AtomicInteger remaining = new AtomicInteger(expectedCount);
            AtomicBoolean initialSnapshotsComplete = new AtomicBoolean();
            Map<String, ValueEventListener> installed = new LinkedHashMap<>();
            for (ListenerTarget target : targets) {
                AtomicBoolean firstValue = new AtomicBoolean();
                ValueEventListener listener = new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!isCurrent(expectedEpoch, expectedGeneration)
                                || isDisposedScope(requestedRevision)) return;
                        if (target.kind == TargetKind.PRESENCE) {
                            acceptPresence(target.userId, snapshot, expectedEpoch, expectedGeneration);
                        } else {
                            acceptTyping(target.conversationId, target.userId,
                                    snapshot, expectedEpoch, expectedGeneration);
                        }
                        if (firstValue.compareAndSet(false, true)
                                && remaining.decrementAndGet() == 0
                                && initialSnapshotsComplete.compareAndSet(false, true)
                                && !emitter.isDisposed()) {
                            emitter.onComplete();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        if (!emitter.isDisposed()) emitter.onError(
                                new IllegalStateException("Firebase listener denied or cancelled"));
                    }
                };
                installed.put(target.key, listener);
                target.reference.addValueEventListener(listener);
            }
            synchronized (lock) {
                if (!isCurrentLocked(expectedEpoch, expectedGeneration)
                        || scopeRevision != requestedRevision) {
                    for (Map.Entry<String, ValueEventListener> entry : installed.entrySet()) {
                        detachListener(entry.getKey(), entry.getValue());
                    }
                    emitter.onError(new IllegalStateException("stale Firebase scope"));
                    return;
                }
                for (ListenerTarget target : targets) {
                    ValueEventListener listener = installed.get(target.key);
                    if (target.kind == TargetKind.PRESENCE) presenceListeners.put(target.key, listener);
                    else typingListeners.put(target.key, listener);
                }
            }
            emitter.setCancellable(() -> {
                if (initialSnapshotsComplete.get()) return;
                synchronized (lock) {
                    for (Map.Entry<String, ValueEventListener> entry : installed.entrySet()) {
                        detachListener(entry.getKey(), entry.getValue());
                    }
                    for (String key : installed.keySet()) {
                        presenceListeners.remove(key);
                        typingListeners.remove(key);
                    }
                }
            });
        }).timeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS, Schedulers.computation());
    }

    private Completable startLeaseRenewal(long expectedEpoch, long expectedGeneration) {
        synchronized (lock) {
            if (leaseRenewal != null) leaseRenewal.dispose();
            leaseRenewal = io.reactivex.rxjava3.core.Flowable.interval(
                            RENEW_INTERVAL_SECONDS, RENEW_INTERVAL_SECONDS,
                            TimeUnit.SECONDS, Schedulers.computation())
                    .concatMapCompletable(ignored -> {
                        EphemeralScope renewalScope = scopeSnapshot();
                        long renewalRevision = currentScopeRevision();
                        return renewLease(renewalScope, expectedEpoch, expectedGeneration)
                                .flatMapCompletable(lease -> isCurrentScope(
                                                expectedEpoch, expectedGeneration, renewalRevision)
                                        ? activateLease(lease, renewalScope,
                                                expectedEpoch, expectedGeneration)
                                        : Completable.complete());
                    })
                    .subscribeOn(Schedulers.io())
                    .subscribe(() -> { }, ignored -> markUnavailable(expectedEpoch, expectedGeneration));
        }
        return Completable.complete();
    }

    private Completable waitForServerOffset(
            FirebaseDatabase db, long expectedEpoch, long expectedGeneration) {
        SingleSubject<Long> offset;
        synchronized (lock) {
            offset = SingleSubject.create();
            ValueEventListener listener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    Number number = asNumber(snapshot.getValue());
                    if (number == null) {
                        offset.onError(new IllegalStateException("Firebase server clock unavailable"));
                        return;
                    }
                    synchronized (lock) {
                        serverOffsetEpochMs = number.longValue();
                    }
                    offset.onSuccess(number.longValue());
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    offset.onError(new IllegalStateException("Firebase server clock unavailable"));
                }
            };
            serverOffsetListener = listener;
            db.getReference(".info/serverTimeOffset").addValueEventListener(listener);
        }
        return offset.timeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS, Schedulers.computation())
                .ignoreElement()
                .doOnError(ignored -> markUnavailable(expectedEpoch, expectedGeneration));
    }

    private Completable configureLatestScope(long expectedEpoch, long expectedGeneration) {
        return Completable.defer(() -> {
            final EphemeralScope requestedScope;
            final long requestedRevision;
            synchronized (lock) {
                if (!isCurrentLocked(expectedEpoch, expectedGeneration)) {
                    return Completable.error(new IllegalStateException("stale Firebase bridge"));
                }
                requestedScope = scope;
                requestedRevision = scopeRevision;
            }
            return renewLease(requestedScope, expectedEpoch, expectedGeneration)
                    .flatMapCompletable(lease -> activateLease(lease, requestedScope,
                                    expectedEpoch, expectedGeneration)
                            .andThen(installScope(requestedScope, expectedEpoch,
                                    expectedGeneration, requestedRevision)))
                    .andThen(markReady(expectedEpoch, expectedGeneration, requestedRevision))
                    .onErrorResumeNext(error -> {
                        if (isCurrentSessionGeneration(expectedEpoch, expectedGeneration)
                                && currentScopeRevision() != requestedRevision) {
                            return configureLatestScope(expectedEpoch, expectedGeneration);
                        }
                        return Completable.error(error);
                    });
        });
    }

    private void attachConnectionListener(
            FirebaseDatabase db, long expectedEpoch, long expectedGeneration) {
        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                boolean nextConnected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                boolean wasConnected;
                boolean isReady;
                synchronized (lock) {
                    if (!isCurrentLocked(expectedEpoch, expectedGeneration)) return;
                    wasConnected = connected;
                    connected = nextConnected;
                    isReady = ready;
                }
                connectionEvents.onNext(nextConnected);
                if (wasConnected && !nextConnected && isReady) {
                    markUnavailable(expectedEpoch, expectedGeneration);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                markUnavailable(expectedEpoch, expectedGeneration);
            }
        };
        synchronized (lock) {
            connectionListener = listener;
        }
        db.getReference(".info/connected").addValueEventListener(listener);
    }

    private Completable markReady(
            long expectedEpoch, long expectedGeneration, long expectedRevision) {
        synchronized (lock) {
            if (!isCurrentLocked(expectedEpoch, expectedGeneration)
                    || scopeRevision != expectedRevision) {
                return Completable.error(new IllegalStateException("stale Firebase bridge"));
            }
            ready = true;
            bootstrapping = false;
            configuredScopeRevision = expectedRevision;
        }
        statuses.onNext(new SourceStatus.Ready(expectedEpoch, expectedGeneration));
        emitCurrentSnapshots(expectedEpoch, expectedGeneration);
        return Completable.complete();
    }

    private void acceptPresence(
            String peerId, DataSnapshot snapshot, long expectedEpoch, long expectedGeneration) {
        List<EphemeralTimeRules.PresenceRecord> records = new ArrayList<>();
        Long newestUpdateAt = null;
        for (DataSnapshot bridge : snapshot.getChildren()) {
            Boolean online = bridge.child("online").getValue(Boolean.class);
            Long lastSeen = asLong(bridge.child("lastSeen").getValue());
            Long leaseUntil = asLong(bridge.child("leaseUntil").getValue());
            Long updatedAt = asLong(bridge.child("updatedAt").getValue());
            if (updatedAt != null && (newestUpdateAt == null || updatedAt > newestUpdateAt)) {
                newestUpdateAt = updatedAt;
            }
            records.add(new EphemeralTimeRules.PresenceRecord(
                    Boolean.TRUE.equals(online), lastSeen, leaseUntil == null ? 0L : leaseUntil));
        }
        PeerPresence aggregate = EphemeralTimeRules.aggregatePresence(
                records, System.currentTimeMillis() + serverOffsetEpochMs);
        boolean publish;
        synchronized (lock) {
            if (!isCurrentLocked(expectedEpoch, expectedGeneration)) return;
            currentPresence.put(peerId, aggregate);
            publish = ready;
        }
        if (publish) presenceEvents.onNext(new Stamped<>(
                expectedEpoch, expectedGeneration,
                new PresenceUpdate(peerId, aggregate, newestUpdateAt)));
    }

    private void acceptTyping(
            String conversationId,
            String memberId,
            DataSnapshot snapshot,
            long expectedEpoch,
            long expectedGeneration) {
        String key = typingKey(conversationId, memberId);
        Long expiresAt = asLong(snapshot.child("expiresAt").getValue());
        boolean activeTyping = expiresAt != null
                && EphemeralTimeRules.isTypingActive(
                        expiresAt, System.currentTimeMillis() + serverOffsetEpochMs);
        TypingEvent event = new TypingEvent(
                conversationId, memberId, null, activeTyping,
                activeTyping ? expiresAt : null);
        boolean publish;
        synchronized (lock) {
            if (!isCurrentLocked(expectedEpoch, expectedGeneration)) return;
            if (activeTyping) currentTyping.put(key, event);
            else currentTyping.remove(key);
            publish = ready;
        }
        if (publish) typingEvents.onNext(new Stamped<>(expectedEpoch, expectedGeneration, event));
    }

    private void emitCurrentSnapshots(long expectedEpoch, long expectedGeneration) {
        Map<String, PeerPresence> presence;
        Map<String, TypingEvent> typing;
        synchronized (lock) {
            if (!isCurrentLocked(expectedEpoch, expectedGeneration)) return;
            presence = new LinkedHashMap<>(currentPresence);
            typing = new LinkedHashMap<>(currentTyping);
        }
        for (Map.Entry<String, PeerPresence> entry : presence.entrySet()) {
            presenceEvents.onNext(new Stamped<>(expectedEpoch, expectedGeneration,
                    new PresenceUpdate(entry.getKey(), entry.getValue())));
        }
        for (TypingEvent event : typing.values()) {
            typingEvents.onNext(new Stamped<>(expectedEpoch, expectedGeneration, event));
        }
    }

    private void markUnavailable(long expectedEpoch, long expectedGeneration) {
        synchronized (lock) {
            if (!isCurrentLocked(expectedEpoch, expectedGeneration)) return;
            ready = false;
            bootstrapping = false;
        }
        statuses.onNext(new SourceStatus.Unavailable(expectedEpoch, expectedGeneration));
    }

    private void revokeBestEffort(@Nullable String id) {
        if (id == null || id.isBlank()) return;
        ApiResult.safeApiCall(api.revokeBridge(id)).subscribeOn(Schedulers.io())
                .subscribe(ignored -> { }, ignored -> { });
    }

    private FirebaseRuntime initializeRuntime(String url, long expectedEpoch, long expectedGeneration) {
        FirebaseApp defaultApp = null;
        for (FirebaseApp app : FirebaseApp.getApps(context)) {
            if (FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())) {
                defaultApp = app;
                break;
            }
        }
        if (defaultApp == null) throw new IllegalStateException("Firebase app is not configured");

        FirebaseApp app;
        try {
            String appName = FirebaseAuthQueue.runtimeAppName(expectedEpoch, expectedGeneration);
            app = FirebaseApp.getInstance(appName);
        } catch (IllegalStateException missingNamedApp) {
            app = FirebaseApp.initializeApp(context, defaultApp.getOptions(),
                    FirebaseAuthQueue.runtimeAppName(expectedEpoch, expectedGeneration));
        }
        if (app == null) throw new IllegalStateException("Firebase app is not configured");
        FirebaseAuth auth = FirebaseAuth.getInstance(app);
        FirebaseDatabase db = FirebaseDatabase.getInstance(app, url);
        synchronized (lock) {
            if (persistenceConfigured.add(db)) db.setPersistenceEnabled(false);
        }
        db.goOnline();
        return new FirebaseRuntime(app, auth, db);
    }

    private void disposeUnadmittedRuntime(FirebaseRuntime runtime) {
        Completable cleanup = Completable.fromAction(() -> {
            runtime.database.purgeOutstandingWrites();
            runtime.database.goOffline();
        }).andThen(Completable.fromAction(runtime.auth::signOut))
                .doFinally(runtime.app::delete);
        authQueue.enqueue(cleanup).subscribeOn(Schedulers.io())
                .subscribe(() -> { }, ignored -> { });
    }

    private Completable waitForConnection(
            FirebaseDatabase db, long expectedEpoch, long expectedGeneration) {
        attachConnectionListener(db, expectedEpoch, expectedGeneration);
        return connectionEvents.filter(Boolean.TRUE::equals)
                .firstOrError()
                .timeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS, Schedulers.computation())
                .ignoreElement();
    }

    private boolean validDatabaseUrl(@Nullable String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean validLeaseUntil(@Nullable String leaseUntilValue) {
        if (leaseUntilValue == null || leaseUntilValue.isBlank()) return false;
        final long leaseUntil;
        try {
            leaseUntil = Instant.parse(leaseUntilValue).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return false;
        }
        long now = System.currentTimeMillis() + serverOffsetEpochMs;
        return leaseUntil > now && leaseUntil <= now + EphemeralTimeRules.MAX_BRIDGE_LEASE_MS;
    }

    private static long leaseUntilEpochMs(FirebaseLeaseResponse lease) {
        String value = lease.getLeaseUntil();
        if (value == null) throw new IllegalStateException("invalid Firebase lease response");
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            throw new IllegalStateException("invalid Firebase lease response");
        }
    }

    private Map<String, Object> offlinePresencePayload() {
        Map<String, Object> value = new HashMap<>();
        value.put("online", Boolean.FALSE);
        value.put("lastSeen", ServerValue.TIMESTAMP);
        value.put("updatedAt", ServerValue.TIMESTAMP);
        value.put("leaseUntil", leaseUntilEpochMs);
        return value;
    }

    private long currentScopeRevision() {
        synchronized (lock) { return scopeRevision; }
    }

    private EphemeralScope scopeSnapshot() {
        synchronized (lock) { return scope; }
    }

    private boolean isDisposedScope(long revision) {
        synchronized (lock) { return !active || scopeRevision != revision; }
    }

    private boolean isCurrentScope(long epoch, long expectedGeneration, long revision) {
        synchronized (lock) {
            return isCurrentLocked(epoch, expectedGeneration) && scopeRevision == revision;
        }
    }

    private boolean isCurrentSessionGeneration(long epoch, long expectedGeneration) {
        synchronized (lock) { return isCurrentLocked(epoch, expectedGeneration); }
    }

    private boolean isCurrent(long expectedEpoch, long expectedGeneration) {
        synchronized (lock) { return isCurrentLocked(expectedEpoch, expectedGeneration); }
    }

    private boolean isCurrentLocked(long expectedEpoch, long expectedGeneration) {
        return active && sessionEpoch == expectedEpoch && generation == expectedGeneration
                && tokenRefresher.isSessionCurrent(expectedEpoch);
    }

    private boolean hasDefaultFirebaseApp() {
        for (FirebaseApp app : FirebaseApp.getApps(context)) {
            if (FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())) return true;
        }
        return false;
    }

    private void removeScopeListenersLocked() {
        for (Map.Entry<String, ValueEventListener> entry : presenceListeners.entrySet()) {
            detachListener(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, ValueEventListener> entry : typingListeners.entrySet()) {
            detachListener(entry.getKey(), entry.getValue());
        }
        presenceListeners.clear();
        typingListeners.clear();
    }

    private void removeListenersLocked() {
        removeScopeListenersLocked();
        if (database != null && connectionListener != null) {
            database.getReference(".info/connected").removeEventListener(connectionListener);
        }
        if (database != null && serverOffsetListener != null) {
            database.getReference(".info/serverTimeOffset").removeEventListener(serverOffsetListener);
        }
        connectionListener = null;
        serverOffsetListener = null;
        connectionEvents.onNext(Boolean.FALSE);
    }

    private void detachListener(String key, ValueEventListener listener) {
        if (database == null) return;
        if (key.startsWith("presence:")) {
            database.getReference("presence").child(key.substring("presence:".length()))
                    .removeEventListener(listener);
            return;
        }
        int separator = key.indexOf('/');
        if (separator <= 0) return;
        database.getReference("typing").child(key.substring(0, separator))
                .child(key.substring(separator + 1)).removeEventListener(listener);
    }

    private static String typingKey(String conversationId, String userId) {
        return conversationId + "/" + userId;
    }

    private static Long asLong(@Nullable Object value) {
        Number number = asNumber(value);
        return number == null ? null : number.longValue();
    }

    @Nullable
    private static Number asNumber(@Nullable Object value) {
        return value instanceof Number number ? number : null;
    }

    private static <T> Single<T> taskSingle(Task<T> task) {
        return Single.create(emitter -> task.addOnCompleteListener(completed -> {
            if (emitter.isDisposed()) return;
            if (completed.isSuccessful() && completed.getResult() != null) {
                emitter.onSuccess(completed.getResult());
            } else {
                Exception exception = completed.getException();
                emitter.onError(exception == null
                        ? new IllegalStateException("Firebase task failed") : exception);
            }
        }));
    }

    private static <T> Single<T> requiredBody(retrofit2.Call<T> call) {
        return ApiResult.safeApiCall(call).flatMap(result -> {
            if (result instanceof ApiResult.Success<?> success && success.getBody() != null) {
                @SuppressWarnings("unchecked") T body = (T) success.getBody();
                return Single.just(body);
            }
            return Single.error(new IllegalStateException("Firebase bridge API unavailable"));
        });
    }

    private static Completable taskCompletable(Task<Void> task) {
        return Completable.create(emitter -> task.addOnCompleteListener(completed -> {
            if (emitter.isDisposed()) return;
            if (completed.isSuccessful()) emitter.onComplete();
            else {
                Exception exception = completed.getException();
                emitter.onError(exception == null
                        ? new IllegalStateException("Firebase task failed") : exception);
            }
        }));
    }

    private record FirebaseRuntime(FirebaseApp app, FirebaseAuth auth, FirebaseDatabase database) { }
    private record ListenerTarget(
            String key,
            DatabaseReference reference,
            TargetKind kind,
            String userId,
            @Nullable String conversationId) { }
    private enum TargetKind { PRESENCE, TYPING }
}

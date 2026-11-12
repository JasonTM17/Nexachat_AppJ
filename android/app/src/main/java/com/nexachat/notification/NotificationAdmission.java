package com.nexachat.notification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.data.SyncEngine;
import com.nexachat.data.TimeAndJwt;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Fails closed unless the current account, session revision and cached membership all match. */
@Singleton
public final class NotificationAdmission {
    private final TokenRefresher tokenRefresher;
    private final TokenStore tokenStore;
    private final AppDatabase appDatabase;
    private final ConversationDao conversationDao;
    private final SyncEngine syncEngine;
    private final Moshi moshi;
    private final Scheduler ioScheduler;

    @Inject
    public NotificationAdmission(
            @NonNull TokenRefresher tokenRefresher,
            @NonNull TokenStore tokenStore,
            @NonNull AppDatabase appDatabase,
            @NonNull ConversationDao conversationDao,
            @NonNull SyncEngine syncEngine,
            @NonNull Moshi moshi) {
        this(tokenRefresher, tokenStore, appDatabase, conversationDao, syncEngine, moshi, Schedulers.io());
    }

    @VisibleForTesting
    public NotificationAdmission(
            @NonNull TokenRefresher tokenRefresher,
            @NonNull TokenStore tokenStore,
            @NonNull AppDatabase appDatabase,
            @NonNull ConversationDao conversationDao,
            @NonNull SyncEngine syncEngine,
            @NonNull Moshi moshi,
            @NonNull Scheduler ioScheduler) {
        this.tokenRefresher = tokenRefresher;
        this.tokenStore = tokenStore;
        this.appDatabase = appDatabase;
        this.conversationDao = conversationDao;
        this.syncEngine = syncEngine;
        this.moshi = moshi;
        this.ioScheduler = ioScheduler;
    }

    /** Captures a currently active account epoch only when it matches the push audience. */
    @NonNull
    public Maybe<NotificationSession> admitPush(
            @Nullable String recipientId, @Nullable String conversationId) {
        if (!hasText(recipientId) || !hasText(conversationId)) return Maybe.empty();
        return currentCredential(null)
                .filter(credential -> recipientId.equals(
                        TimeAndJwt.userIdFromJwt(credential.accessToken, moshi)))
                .filter(credential -> tokenRefresher.isSessionCurrent(credential.epoch))
                .flatMap(credential -> conversationDao.byId(conversationId)
                        .filter(ignored -> tokenRefresher.isSessionCurrent(credential.epoch))
                        .map(ignored -> new NotificationSession(recipientId, credential.epoch)))
                .subscribeOn(ioScheduler);
    }

    /** Rechecks audience and local membership before and after authoritative delta sync. */
    @NonNull
    public Single<Boolean> syncAndCheck(
            @NonNull String recipientId, long sessionEpoch, @NonNull String conversationId) {
        return isCurrent(recipientId, sessionEpoch, conversationId)
                .flatMap(current -> current
                        ? syncEngine.sync().andThen(isCurrent(
                                recipientId, sessionEpoch, conversationId))
                        : Single.just(false))
                .subscribeOn(ioScheduler);
    }

    @NonNull
    public Single<Boolean> isCurrent(
            @NonNull String recipientId, long sessionEpoch, @NonNull String conversationId) {
        return currentCredential(sessionEpoch)
                .filter(credential -> recipientId.equals(
                        TimeAndJwt.userIdFromJwt(credential.accessToken, moshi)))
                .filter(credential -> tokenRefresher.isSessionCurrent(credential.epoch))
                .flatMap(ignored -> conversationDao.byId(conversationId)
                        .map(row -> tokenRefresher.isSessionCurrent(sessionEpoch)))
                .defaultIfEmpty(false);
    }

    /**
     * Fences a short notification or navigation side effect with the session
     * epoch, token subject, and local membership row in the same Room transaction.
     * This uses the same session-lock then database-lock order as SyncEngine.
     */
    public boolean runIfCurrent(
            @NonNull String recipientId,
            long sessionEpoch,
            @NonNull String conversationId,
            @NonNull Consumer<ConversationEntity> action) {
        if (!hasText(recipientId) || !hasText(conversationId) || sessionEpoch <= 0L) {
            return false;
        }
        AtomicBoolean actionRan = new AtomicBoolean(false);
        boolean sessionCurrent = tokenRefresher.runIfSessionCurrent(sessionEpoch, () ->
                {
                    Tokens tokens = tokenStore.readTokens().blockingGet();
                    if (tokens == null || !recipientId.equals(
                            TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi))) {
                        return;
                    }
                    appDatabase.runInTransaction(() -> {
                        if (!tokenRefresher.isSessionCurrent(sessionEpoch)) return;
                        ConversationEntity conversation = conversationDao.byIdSync(conversationId);
                        if (conversation == null) return;
                        action.accept(conversation);
                        actionRan.set(true);
                    });
                });
        return sessionCurrent && actionRan.get();
    }

    private Maybe<SessionCredential> currentCredential(@Nullable Long expectedEpoch) {
        long observedEpoch = tokenRefresher.sessionEpoch();
        if (expectedEpoch != null) {
            if (observedEpoch != expectedEpoch || !tokenRefresher.isSessionCurrent(expectedEpoch)) {
                return Maybe.empty();
            }
            return tokenStore.readTokens()
                    .filter(ignored -> tokenRefresher.isSessionCurrent(expectedEpoch))
                    .map(tokens -> new SessionCredential(tokens, expectedEpoch));
        }
        if (tokenRefresher.isSessionCurrent(observedEpoch)) {
            return tokenStore.readTokens()
                    .filter(ignored -> tokenRefresher.isSessionCurrent(observedEpoch))
                    .map(tokens -> new SessionCredential(tokens, observedEpoch));
        }
        return tokenRefresher.refreshIfNeeded(null).flatMapMaybe(accessToken -> {
            Optional<String> restored = accessToken;
            if (restored.isEmpty()) return Maybe.empty();
            long currentEpoch = tokenRefresher.sessionEpoch();
            return tokenRefresher.isSessionCurrent(currentEpoch)
                    ? Maybe.just(new SessionCredential(restored.get(), currentEpoch))
                    : Maybe.empty();
        });
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static final class SessionCredential {
        private final String accessToken;
        private final long epoch;

        private SessionCredential(Tokens tokens, long epoch) {
            this(tokens.getAccessToken(), epoch);
        }

        private SessionCredential(String accessToken, long epoch) {
            this.accessToken = accessToken;
            this.epoch = epoch;
        }
    }

    public record NotificationSession(String recipientId, long sessionEpoch) { }
}

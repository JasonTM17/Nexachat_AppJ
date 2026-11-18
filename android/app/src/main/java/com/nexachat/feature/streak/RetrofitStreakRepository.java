package com.nexachat.feature.streak;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.network.ApiCalls;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Retrofit-backed {@link StreakRepository}. */
@Singleton
public final class RetrofitStreakRepository implements StreakRepository {

    /** Writes the badge values of one inbox row. */
    @FunctionalInterface
    public interface BadgeCache {
        void put(@NonNull String conversationId, int count, boolean atRisk);
    }

    private final StreakApi api;
    private final StreakStore store;
    private final BadgeCache badgeCache;
    private final Clock clock;

    @Inject
    public RetrofitStreakRepository(
            @NonNull StreakApi api,
            @NonNull StreakStore store,
            @NonNull ConversationDao conversationDao) {
        this(api, store, (id, count, atRisk) -> conversationDao.updateStreak(id, count, atRisk)
                        .subscribeOn(Schedulers.io())
                        .subscribe(() -> { }, ignored -> { }),
                Clock.systemDefaultZone());
    }

    @VisibleForTesting
    public RetrofitStreakRepository(
            @NonNull StreakApi api,
            @NonNull StreakStore store,
            @NonNull BadgeCache badgeCache,
            @NonNull Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.store = Objects.requireNonNull(store, "store");
        this.badgeCache = Objects.requireNonNull(badgeCache, "badgeCache");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @NonNull
    @Override
    public ApiCalls.Cancellable loadMine(@NonNull Callback<StreakDto> callback) {
        return enqueue(api.getMine(), callback, body -> { });
    }

    @NonNull
    @Override
    public ApiCalls.Cancellable loadConversation(
            @NonNull String conversationId, @NonNull Callback<StreakDto> callback) {
        return enqueue(api.getConversationStreak(conversationId), callback,
                body -> badgeCache.put(conversationId, body.getCount(), body.isAtRisk()));
    }

    @Override
    public boolean isCheckInDue() {
        return StreakRules.shouldCheckIn(store.lastCheckInDay(), today());
    }

    @NonNull
    @Override
    public ApiCalls.Cancellable checkIn(@NonNull Callback<StreakDto> callback) {
        // The once-a-day gate is device-local, so the stored day is the local
        // day of this success rather than the server's streak day: a server
        // zone ahead of the device would otherwise skip a day.
        LocalDate today = today();
        return enqueue(api.checkIn(), callback, body -> store.saveLastCheckInDay(today.toString()));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static ApiCalls.Cancellable enqueue(
            retrofit2.Call<StreakDto> call,
            Callback<StreakDto> callback,
            java.util.function.Consumer<StreakDto> onBody) {
        return ApiCalls.enqueue(call, new ApiCalls.ResultCallback<StreakDto>() {
            @Override
            public void onSuccess(StreakDto body, int status) {
                if (body == null) {
                    callback.onFailure();
                    return;
                }
                onBody.accept(body.checkInView());
                callback.onSuccess(body);
            }

            @Override
            public void onFailure(@NonNull ApiCalls.Failure failure) {
                callback.onFailure();
            }
        });
    }
}

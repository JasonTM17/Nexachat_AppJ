package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.Result;
import io.reactivex.rxjava3.core.Single;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import retrofit2.Call;

/**
 * Serializes caller-scoped preference REST writes per conversation lane. While
 * one HTTP request is in flight, newer intents for the same lane are coalesced
 * and only the latest value is sent after the in-flight call completes. When
 * the latest intent already matches server-confirmed state, no request is sent.
 */
public final class ConversationPreferenceNetworkQueue {

    public interface Executor {
        @NonNull
        Single<Result<Void>> execute(@NonNull Call<Void> call);
    }

    public interface OutcomeHandler {
        @NonNull
        Single<Result<Void>> onArchiveNetworkComplete(
                @NonNull String conversationId,
                long version,
                boolean attemptedArchived,
                @NonNull Result<Void> result);

        @NonNull
        Single<Result<Void>> onMuteNetworkComplete(
                @NonNull String conversationId,
                long version,
                @NonNull MuteState attempted,
                @NonNull Result<Void> result);

        @NonNull
        Single<Result<Void>> onPinNetworkComplete(
                @NonNull String conversationId,
                long version,
                @NonNull PinState attempted,
                @NonNull Result<Void> result);
    }

    public static final class MuteState {
        public final boolean muted;
        @Nullable public final Long mutedUntilEpochMs;

        public MuteState(boolean muted, @Nullable Long mutedUntilEpochMs) {
            this.muted = muted;
            this.mutedUntilEpochMs = mutedUntilEpochMs;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MuteState state)) return false;
            return muted == state.muted
                    && Objects.equals(mutedUntilEpochMs, state.mutedUntilEpochMs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(muted, mutedUntilEpochMs);
        }
    }

    public static final class PinState {
        public final boolean pinned;
        @Nullable public final Long pinnedAtEpochMs;

        public PinState(boolean pinned, @Nullable Long pinnedAtEpochMs) {
            this.pinned = pinned;
            this.pinnedAtEpochMs = pinnedAtEpochMs;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PinState state)) return false;
            return pinned == state.pinned
                    && Objects.equals(pinnedAtEpochMs, state.pinnedAtEpochMs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pinned, pinnedAtEpochMs);
        }
    }

    private static final class ArchiveLane {
        boolean seeded;
        boolean confirmedArchived;
        @Nullable Boolean pendingArchived;
        long pendingVersion;
        boolean networkBusy;
    }

    private static final class MuteLane {
        boolean seeded;
        boolean confirmedMuted;
        @Nullable Long confirmedUntil;
        @Nullable MuteState pending;
        long pendingVersion;
        boolean networkBusy;
    }

    private static final class PinLane {
        boolean seeded;
        boolean confirmedPinned;
        @Nullable Long confirmedPinnedAt;
        @Nullable PinState pending;
        long pendingVersion;
        boolean networkBusy;
    }

    private final Executor executor;
    private final OutcomeHandler outcomeHandler;
    private final Map<String, ArchiveLane> archiveLanes = new HashMap<>();
    private final Map<String, MuteLane> muteLanes = new HashMap<>();
    private final Map<String, PinLane> pinLanes = new HashMap<>();

    public ConversationPreferenceNetworkQueue(
            @NonNull Executor executor,
            @NonNull OutcomeHandler outcomeHandler) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.outcomeHandler = Objects.requireNonNull(outcomeHandler, "outcomeHandler");
    }

    public void seedArchiveIfUnknown(@NonNull String conversationId, boolean archived) {
        synchronized (archiveLanes) {
            ArchiveLane lane = archiveLane(conversationId);
            if (!lane.seeded) {
                lane.seeded = true;
                lane.confirmedArchived = archived;
            }
        }
    }

    public void noteServerArchive(@NonNull String conversationId, boolean archived) {
        synchronized (archiveLanes) {
            ArchiveLane lane = archiveLane(conversationId);
            lane.seeded = true;
            lane.confirmedArchived = archived;
        }
    }

    public boolean confirmedArchived(@NonNull String conversationId) {
        synchronized (archiveLanes) {
            return archiveLane(conversationId).confirmedArchived;
        }
    }

  public void seedMuteIfUnknown(
            @NonNull String conversationId, boolean muted, @Nullable Long until) {
        synchronized (muteLanes) {
            MuteLane lane = muteLane(conversationId);
            if (!lane.seeded) {
                lane.seeded = true;
                lane.confirmedMuted = muted;
                lane.confirmedUntil = until;
            }
        }
    }

    public void noteServerMute(
            @NonNull String conversationId, boolean muted, @Nullable Long until) {
        synchronized (muteLanes) {
            MuteLane lane = muteLane(conversationId);
            lane.seeded = true;
            lane.confirmedMuted = muted;
            lane.confirmedUntil = until;
        }
    }

    @NonNull
    public MuteState confirmedMute(@NonNull String conversationId) {
        synchronized (muteLanes) {
            MuteLane lane = muteLane(conversationId);
            return new MuteState(lane.confirmedMuted, lane.confirmedUntil);
        }
    }

    public void seedPinIfUnknown(
            @NonNull String conversationId, boolean pinned, @Nullable Long pinnedAt) {
        synchronized (pinLanes) {
            PinLane lane = pinLane(conversationId);
            if (!lane.seeded) {
                lane.seeded = true;
                lane.confirmedPinned = pinned;
                lane.confirmedPinnedAt = pinnedAt;
            }
        }
    }

    public void noteServerPin(
            @NonNull String conversationId, boolean pinned, @Nullable Long pinnedAt) {
        synchronized (pinLanes) {
            PinLane lane = pinLane(conversationId);
            lane.seeded = true;
            lane.confirmedPinned = pinned;
            lane.confirmedPinnedAt = pinnedAt;
        }
    }

    @NonNull
    public PinState confirmedPin(@NonNull String conversationId) {
        synchronized (pinLanes) {
            PinLane lane = pinLane(conversationId);
            return new PinState(lane.confirmedPinned, lane.confirmedPinnedAt);
        }
    }

    @NonNull
    public Single<Result<Void>> submitArchive(
            @NonNull String conversationId,
            @NonNull ConversationPreferenceLanes.Ticket ticket,
            boolean archived,
            @NonNull Function<Boolean, Call<Void>> callForArchived) {
        Objects.requireNonNull(conversationId, "conversationId");
        synchronized (archiveLanes) {
            ArchiveLane lane = archiveLane(conversationId);
            lane.pendingArchived = archived;
            lane.pendingVersion = ticket.version;
            if (lane.networkBusy) {
                return Single.just(success());
            }
            return drainArchive(conversationId, callForArchived, ticket.version);
        }
    }

    @NonNull
    public Single<Result<Void>> submitMute(
            @NonNull String conversationId,
            @NonNull ConversationPreferenceLanes.Ticket ticket,
            @NonNull MuteState desired,
            @NonNull Function<MuteState, Call<Void>> callForState) {
        synchronized (muteLanes) {
            MuteLane lane = muteLane(conversationId);
            lane.pending = desired;
            lane.pendingVersion = ticket.version;
            if (lane.networkBusy) {
                return Single.just(success());
            }
            return drainMute(conversationId, callForState, ticket.version);
        }
    }

    @NonNull
    public Single<Result<Void>> submitPin(
            @NonNull String conversationId,
            @NonNull ConversationPreferenceLanes.Ticket ticket,
            @NonNull PinState desired,
            @NonNull Function<PinState, Call<Void>> callForState) {
        synchronized (pinLanes) {
            PinLane lane = pinLane(conversationId);
            lane.pending = desired;
            lane.pendingVersion = ticket.version;
            if (lane.networkBusy) {
                return Single.just(success());
            }
            return drainPin(conversationId, callForState, ticket.version);
        }
    }

    @NonNull
    private Single<Result<Void>> drainArchive(
            @NonNull String conversationId,
            @NonNull Function<Boolean, Call<Void>> callForArchived,
            long awaitVersion) {
        ArchiveLane lane = archiveLane(conversationId);
        if (lane.pendingArchived == null) {
            return Single.just(success());
        }
        boolean target = lane.pendingArchived;
        long flightVersion = lane.pendingVersion;
        lane.pendingArchived = null;
        if (target == lane.confirmedArchived) {
            return finishArchiveLane(conversationId, callForArchived, awaitVersion, flightVersion);
        }
        lane.networkBusy = true;
        return executor.execute(callForArchived.apply(target))
                .flatMap(result -> outcomeHandler.onArchiveNetworkComplete(
                        conversationId, flightVersion, target, result))
                .flatMap(resolved -> afterArchiveFlight(
                        conversationId, callForArchived, flightVersion, awaitVersion, target,
                        resolved));
    }

    @NonNull
    private Single<Result<Void>> afterArchiveFlight(
            @NonNull String conversationId,
            @NonNull Function<Boolean, Call<Void>> callForArchived,
            long flightVersion,
            long awaitVersion,
            boolean attempted,
            @NonNull Result<Void> resolved) {
        if (resolved instanceof Result.Success<?>) {
            synchronized (archiveLanes) {
                ArchiveLane lane = archiveLane(conversationId);
                lane.confirmedArchived = attempted;
            }
        }
        synchronized (archiveLanes) {
            ArchiveLane lane = archiveLane(conversationId);
            if (lane.pendingArchived != null) {
                long nextAwait = lane.pendingVersion;
                return drainArchive(conversationId, callForArchived, nextAwait)
                        .map(ignored -> flightVersion == awaitVersion ? resolved : success());
            }
            lane.networkBusy = false;
        }
        return flightVersion == awaitVersion ? Single.just(resolved) : Single.just(success());
    }

    @NonNull
    private Single<Result<Void>> finishArchiveLane(
            @NonNull String conversationId,
            @NonNull Function<Boolean, Call<Void>> callForArchived,
            long awaitVersion,
            long settledVersion) {
        synchronized (archiveLanes) {
            ArchiveLane lane = archiveLane(conversationId);
            if (lane.pendingArchived != null) {
                long nextAwait = lane.pendingVersion;
                return drainArchive(conversationId, callForArchived, nextAwait)
                        .map(ignored -> settledVersion == awaitVersion ? success() : success());
            }
            lane.networkBusy = false;
        }
        return Single.just(success());
    }

    @NonNull
    private Single<Result<Void>> drainMute(
            @NonNull String conversationId,
            @NonNull Function<MuteState, Call<Void>> callForState,
            long awaitVersion) {
        MuteLane lane = muteLane(conversationId);
        if (lane.pending == null) {
            return Single.just(success());
        }
        MuteState target = lane.pending;
        long flightVersion = lane.pendingVersion;
        lane.pending = null;
        MuteState confirmed = new MuteState(lane.confirmedMuted, lane.confirmedUntil);
        if (target.equals(confirmed)) {
            return finishMuteLane(conversationId, callForState, awaitVersion, flightVersion);
        }
        lane.networkBusy = true;
        return executor.execute(callForState.apply(target))
                .flatMap(result -> outcomeHandler.onMuteNetworkComplete(
                        conversationId, flightVersion, target, result))
                .flatMap(resolved -> afterMuteFlight(
                        conversationId, callForState, flightVersion, awaitVersion, target,
                        resolved));
    }

    @NonNull
    private Single<Result<Void>> afterMuteFlight(
            @NonNull String conversationId,
            @NonNull Function<MuteState, Call<Void>> callForState,
            long flightVersion,
            long awaitVersion,
            @NonNull MuteState attempted,
            @NonNull Result<Void> resolved) {
        if (resolved instanceof Result.Success<?>) {
            synchronized (muteLanes) {
                MuteLane lane = muteLane(conversationId);
                lane.confirmedMuted = attempted.muted;
                lane.confirmedUntil = attempted.mutedUntilEpochMs;
            }
        }
        synchronized (muteLanes) {
            MuteLane lane = muteLane(conversationId);
            if (lane.pending != null) {
                long nextAwait = lane.pendingVersion;
                return drainMute(conversationId, callForState, nextAwait)
                        .map(ignored -> flightVersion == awaitVersion ? resolved : success());
            }
            lane.networkBusy = false;
        }
        return flightVersion == awaitVersion ? Single.just(resolved) : Single.just(success());
    }

    @NonNull
    private Single<Result<Void>> finishMuteLane(
            @NonNull String conversationId,
            @NonNull Function<MuteState, Call<Void>> callForState,
            long awaitVersion,
            long settledVersion) {
        synchronized (muteLanes) {
            MuteLane lane = muteLane(conversationId);
            if (lane.pending != null) {
                long nextAwait = lane.pendingVersion;
                return drainMute(conversationId, callForState, nextAwait)
                        .map(ignored -> settledVersion == awaitVersion ? success() : success());
            }
            lane.networkBusy = false;
        }
        return Single.just(success());
    }

    @NonNull
    private Single<Result<Void>> drainPin(
            @NonNull String conversationId,
            @NonNull Function<PinState, Call<Void>> callForState,
            long awaitVersion) {
        PinLane lane = pinLane(conversationId);
        if (lane.pending == null) {
            return Single.just(success());
        }
        PinState target = lane.pending;
        long flightVersion = lane.pendingVersion;
        lane.pending = null;
        PinState confirmed = new PinState(lane.confirmedPinned, lane.confirmedPinnedAt);
        if (target.equals(confirmed)) {
            return finishPinLane(conversationId, callForState, awaitVersion, flightVersion);
        }
        lane.networkBusy = true;
        return executor.execute(callForState.apply(target))
                .flatMap(result -> outcomeHandler.onPinNetworkComplete(
                        conversationId, flightVersion, target, result))
                .flatMap(resolved -> afterPinFlight(
                        conversationId, callForState, flightVersion, awaitVersion, target,
                        resolved));
    }

    @NonNull
    private Single<Result<Void>> afterPinFlight(
            @NonNull String conversationId,
            @NonNull Function<PinState, Call<Void>> callForState,
            long flightVersion,
            long awaitVersion,
            @NonNull PinState attempted,
            @NonNull Result<Void> resolved) {
        if (resolved instanceof Result.Success<?>) {
            synchronized (pinLanes) {
                PinLane lane = pinLane(conversationId);
                lane.confirmedPinned = attempted.pinned;
                lane.confirmedPinnedAt = attempted.pinnedAtEpochMs;
            }
        }
        synchronized (pinLanes) {
            PinLane lane = pinLane(conversationId);
            if (lane.pending != null) {
                long nextAwait = lane.pendingVersion;
                return drainPin(conversationId, callForState, nextAwait)
                        .map(ignored -> flightVersion == awaitVersion ? resolved : success());
            }
            lane.networkBusy = false;
        }
        return flightVersion == awaitVersion ? Single.just(resolved) : Single.just(success());
    }

    @NonNull
    private Single<Result<Void>> finishPinLane(
            @NonNull String conversationId,
            @NonNull Function<PinState, Call<Void>> callForState,
            long awaitVersion,
            long settledVersion) {
        synchronized (pinLanes) {
            PinLane lane = pinLane(conversationId);
            if (lane.pending != null) {
                long nextAwait = lane.pendingVersion;
                return drainPin(conversationId, callForState, nextAwait)
                        .map(ignored -> settledVersion == awaitVersion ? success() : success());
            }
            lane.networkBusy = false;
        }
        return Single.just(success());
    }

    @NonNull
    private ArchiveLane archiveLane(@NonNull String conversationId) {
        return archiveLanes.computeIfAbsent(conversationId, ignored -> new ArchiveLane());
    }

    @NonNull
    private MuteLane muteLane(@NonNull String conversationId) {
        return muteLanes.computeIfAbsent(conversationId, ignored -> new MuteLane());
    }

    @NonNull
    private PinLane pinLane(@NonNull String conversationId) {
        return pinLanes.computeIfAbsent(conversationId, ignored -> new PinLane());
    }

    synchronized void resetForTests() {
        archiveLanes.clear();
        muteLanes.clear();
        pinLanes.clear();
    }

    @NonNull
    private static Result<Void> success() {
        return new Result.Success<>(null);
    }
}

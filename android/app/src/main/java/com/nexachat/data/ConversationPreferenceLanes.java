package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Monotonic request ordering for caller-scoped conversation preferences. Each
 * conversation lane (archive, mute, pin) keeps its own version; stale network
 * completions must not roll back or overwrite a newer local intent.
 */
public final class ConversationPreferenceLanes {

    public enum Kind {
        ARCHIVE,
        MUTE,
        PIN
    }

    public static final class Ticket {
        public final long version;

        Ticket(long version) {
            this.version = version;
        }
    }

    private static final class Lane {
        long version;
        int inFlight;
    }

    private final Map<String, EnumMap<Kind, Lane>> lanes = new HashMap<>();

    /** Starts one ordered preference write and returns its version ticket. */
    @NonNull
    public synchronized Ticket begin(@NonNull String conversationId, @NonNull Kind kind) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(kind, "kind");
        EnumMap<Kind, Lane> conversation =
                lanes.computeIfAbsent(conversationId, id -> new EnumMap<>(Kind.class));
        Lane lane = conversation.computeIfAbsent(kind, ignored -> new Lane());
        lane.version++;
        lane.inFlight++;
        return new Ticket(lane.version);
    }

    /** True when a completion for {@code version} is too old to mutate local state. */
    public synchronized boolean isStale(
            @NonNull String conversationId, @NonNull Kind kind, long version) {
        Lane lane = lane(conversationId, kind);
        return lane == null || version < lane.version;
    }

    /** Marks one in-flight request complete without touching state when stale. */
    public synchronized void finish(
            @NonNull String conversationId, @NonNull Kind kind, long version) {
        Lane lane = lane(conversationId, kind);
        if (lane == null || version > lane.version) {
            return;
        }
        lane.inFlight = Math.max(0, lane.inFlight - 1);
    }

    /**
     * While any ordered write is still in flight for this lane, REST merge must
     * not overwrite the optimistic Room columns for that preference.
     */
    public synchronized boolean preserveLocalOverServer(
            @NonNull String conversationId, @NonNull Kind kind) {
        Lane lane = lane(conversationId, kind);
        return lane != null && lane.inFlight > 0;
    }

  @Nullable
    private Lane lane(@NonNull String conversationId, @NonNull Kind kind) {
        EnumMap<Kind, Lane> conversation = lanes.get(conversationId);
        return conversation == null ? null : conversation.get(kind);
    }

    synchronized void resetForTests() {
        lanes.clear();
    }
}

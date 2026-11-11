package com.nexachat.core.realtime.ephemeral;

import androidx.annotation.NonNull;
import com.nexachat.data.TypingEvent;
import io.reactivex.rxjava3.core.Flowable;

/** Fakeable transport boundary for ephemeral presence and typing signals. */
public interface EphemeralStateSource {
    @NonNull Flowable<Stamped<PresenceUpdate>> presenceUpdates();
    @NonNull Flowable<Stamped<TypingEvent>> typingUpdates();
    @NonNull Flowable<SourceStatus> statusUpdates();

    void start(long sessionEpoch, long generation, @NonNull EphemeralScope scope);
    void updateScope(long sessionEpoch, long generation, @NonNull EphemeralScope scope);
    void stop();

    /** False means this source cannot accept the event and the router should fall back. */
    boolean sendTyping(@NonNull String conversationId);
    void clearTyping(@NonNull String conversationId);

    record Stamped<T>(long sessionEpoch, long generation, @NonNull T value) { }

    sealed interface SourceStatus permits SourceStatus.Starting, SourceStatus.Ready,
            SourceStatus.Unavailable {
        long sessionEpoch();
        long generation();

        record Starting(long sessionEpoch, long generation) implements SourceStatus { }
        record Ready(long sessionEpoch, long generation) implements SourceStatus { }
        record Unavailable(long sessionEpoch, long generation) implements SourceStatus { }
    }
}

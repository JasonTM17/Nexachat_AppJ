package com.nexachat.core.realtime.ephemeral;

import androidx.annotation.NonNull;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.data.PeerPresence;
import com.nexachat.data.TimeAndJwt;
import com.nexachat.data.TypingEvent;
import io.reactivex.rxjava3.core.Flowable;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Adapts the existing STOMP stream without changing durable realtime handling. */
@Singleton
public final class StompEphemeralStateSource {
    private final RealtimeSession session;
    private final TokenRefresher tokenRefresher;

    @Inject
    public StompEphemeralStateSource(
            @NonNull RealtimeSession session,
            @NonNull TokenRefresher tokenRefresher) {
        this.session = session;
        this.tokenRefresher = tokenRefresher;
    }

    @NonNull
    public Flowable<EphemeralStateSource.Stamped<PresenceUpdate>> presenceUpdates() {
        return session.getEvents()
                .ofType(ChatEvent.Presence.class)
                .filter(event -> event.getUserId() != null && !event.getUserId().isBlank())
                .map(event -> new EphemeralStateSource.Stamped<>(
                        tokenRefresher.sessionEpoch(), 0L,
                        new PresenceUpdate(event.getUserId(), new PeerPresence(
                                event.getOnline(), TimeAndJwt.isoToEpochMsOrNull(event.getLastSeenAt())),
                                TimeAndJwt.isoToEpochMsOrNull(event.getServerTs()))));
    }

    @NonNull
    public Flowable<EphemeralStateSource.Stamped<TypingEvent>> typingUpdates() {
        return session.getEvents()
                .ofType(ChatEvent.UserTyping.class)
                .filter(event -> event.getConversationId() != null)
                .map(event -> new EphemeralStateSource.Stamped<>(
                        tokenRefresher.sessionEpoch(), 0L,
                        new TypingEvent(
                                event.getConversationId(), event.getUserId(), event.getDisplayName(),
                                true, null)));
    }

    public boolean sendTyping(@NonNull String conversationId) {
        return session.sendTyping(conversationId);
    }
}

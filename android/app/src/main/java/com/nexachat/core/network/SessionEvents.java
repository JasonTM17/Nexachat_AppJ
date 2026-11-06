package com.nexachat.core.network;

import javax.inject.Inject;
import javax.inject.Singleton;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;

/**
 * App-wide session signals. Emitted when the session is definitively dead
 * (refresh rejected, manual logout) and collected once in the navigation host
 * to force a return to Login.
 *
 * <p>{@code tryEmit} so callers on OkHttp threads never suspend. Replay of 1
 * keeps a LoggedOut that fired before the collector subscribed.
 */
@Singleton
public final class SessionEvents {
    private final MutableSharedFlow<Event> events =
            SharedFlowKt.MutableSharedFlow(1, 1, BufferOverflow.DROP_OLDEST);

    @Inject
    public SessionEvents() {
    }

    public SharedFlow<Event> getEvents() {
        return events;
    }

    public void emitLoggedOut() {
        events.tryEmit(Event.LoggedOut.INSTANCE);
    }

    public interface Event {
        final class LoggedOut implements Event {
            public static final LoggedOut INSTANCE = new LoggedOut();

            private LoggedOut() {
            }
        }
    }
}

package com.nexachat.core.network;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.ReplayProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Replays the latest definitive session event to late subscribers. */
@Singleton
public final class SessionEvents {
    private final FlowableProcessor<Event> events = ReplayProcessor.<Event>createWithSize(1).toSerialized();

    @Inject
    public SessionEvents() { }

    public Flowable<Event> getEvents() { return events; }

    public void emitLoggedOut() { events.onNext(Event.LoggedOut.INSTANCE); }
    public void emitSessionAvailable() { events.onNext(Event.SessionAvailable.INSTANCE); }

    public interface Event {
        final class LoggedOut implements Event {
            public static final LoggedOut INSTANCE = new LoggedOut();
            private LoggedOut() { }
        }

        final class SessionAvailable implements Event {
            public static final SessionAvailable INSTANCE = new SessionAvailable();
            private SessionAvailable() { }
        }
    }
}

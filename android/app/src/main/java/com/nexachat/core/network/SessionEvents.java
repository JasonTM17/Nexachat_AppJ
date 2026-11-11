package com.nexachat.core.network;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.ReplayProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Replays the latest definitive session event to late subscribers. */
@Singleton
public final class SessionEvents {
    private final FlowableProcessor<Event> events = ReplayProcessor.<Event>createWithSize(1).toSerialized();
    private final FlowableProcessor<Event> lifecycleEvents = PublishProcessor.<Event>create().toSerialized();

    @Inject
    public SessionEvents() { }

    public Flowable<Event> getEvents() { return events; }

    /** Transient close/refresh edges; these do not replace the replayed session state. */
    public Flowable<Event> getLifecycleEvents() { return lifecycleEvents; }

    public void emitLoggedOut() { events.onNext(Event.LoggedOut.INSTANCE); }

    public void emitReauthenticationRequired() {
        events.onNext(Event.ReauthenticationRequired.INSTANCE);
    }

    public void emitSessionAvailable() { events.onNext(Event.SessionAvailable.INSTANCE); }

    public void emitSessionClosing() { lifecycleEvents.onNext(Event.SessionClosing.INSTANCE); }

    public void emitSessionRefreshed() { lifecycleEvents.onNext(Event.SessionRefreshed.INSTANCE); }

    public interface Event {
        final class LoggedOut implements Event {
            public static final LoggedOut INSTANCE = new LoggedOut();
            private LoggedOut() { }
        }

        final class ReauthenticationRequired implements Event {
            public static final ReauthenticationRequired INSTANCE = new ReauthenticationRequired();

            private ReauthenticationRequired() { }
        }

        final class SessionAvailable implements Event {
            public static final SessionAvailable INSTANCE = new SessionAvailable();
            private SessionAvailable() { }
        }

        final class SessionClosing implements Event {
            public static final SessionClosing INSTANCE = new SessionClosing();
            private SessionClosing() { }
        }

        final class SessionRefreshed implements Event {
            public static final SessionRefreshed INSTANCE = new SessionRefreshed();
            private SessionRefreshed() { }
        }
    }
}

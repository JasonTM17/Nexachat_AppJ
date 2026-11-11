package com.nexachat.core.realtime.ephemeral;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.data.PeerPresence;
import com.nexachat.data.TypingEvent;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class EphemeralStateRouterTest {
    private static final long FIRST_EPOCH = 41L;

    private final AtomicLong currentEpoch = new AtomicLong(FIRST_EPOCH);
    private final TokenRefresher tokenRefresher = mock(TokenRefresher.class);
    private final SessionEvents sessionEvents = mock(SessionEvents.class);
    private final RealtimeSession realtimeSession = mock(RealtimeSession.class);
    private final PublishProcessor<SessionEvents.Event> sessionSignals = PublishProcessor.create();
    private final PublishProcessor<SessionEvents.Event> lifecycleSignals = PublishProcessor.create();
    private final PublishProcessor<ChatEvent> chatEvents = PublishProcessor.create();
    private final FakeSource firebase = new FakeSource();
    private EphemeralStateRouter router;
    private final List<TypingEvent> typing = new ArrayList<>();
    private final List<PresenceUpdate> presence = new ArrayList<>();
    private final List<EphemeralTransport> selections = new ArrayList<>();

    @Before
    public void setUp() {
        when(tokenRefresher.sessionEpoch()).thenAnswer(ignored -> currentEpoch.get());
        when(tokenRefresher.isSessionCurrent(anyLong())).thenAnswer(
                invocation -> currentEpoch.get() == invocation.<Long>getArgument(0));
        when(tokenRefresher.runIfSessionCurrent(anyLong(),
                org.mockito.ArgumentMatchers.<Runnable>any()))
                .thenAnswer(invocation -> {
                    long epoch = invocation.getArgument(0);
                    Runnable action = invocation.getArgument(1);
                    if (currentEpoch.get() != epoch) return false;
                    action.run();
                    return true;
                });
        when(sessionEvents.getEvents()).thenReturn(sessionSignals);
        when(sessionEvents.getLifecycleEvents()).thenReturn(lifecycleSignals);
        when(realtimeSession.getEvents()).thenReturn(chatEvents);
        when(realtimeSession.sendTyping(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    @After
    public void tearDown() {
        if (router != null) router.close();
    }

    @Test
    public void featureFlagOffKeepsStompAndNeverStartsFirebase() {
        createRouter(false);
        router.onAppForeground();

        assertEquals(EphemeralTransport.STOMP, currentTransport());
        assertEquals(0, firebase.startCount);
    }

    @Test
    public void firebaseTakesOnlyEphemeralEventsAfterReadyAndFallbackRestoresStomp() {
        createRouter(true);
        router.typingEvents().subscribe(typing::add);
        router.presenceEvents().subscribe(presence::add);
        router.onAppForeground();

        chatEvents.onNext(new ChatEvent.UserTyping("conversation-1", "peer-1", "Lan"));
        assertEquals(1, typing.size());
        long initialGeneration = firebase.generation;
        firebase.ready();
        assertEquals(EphemeralTransport.FIREBASE, currentTransport());

        chatEvents.onNext(new ChatEvent.UserTyping("conversation-1", "peer-1", "Lan"));
        firebase.emitTyping(new TypingEvent("conversation-1", "peer-2", null, true, 9_000L));
        firebase.emitPresence(new PresenceUpdate("peer-2", new PeerPresence(true, null), 8_000L));
        assertEquals(2, typing.size());
        assertEquals("peer-2", typing.get(1).getUserId());
        assertEquals(1, presence.size());

        firebase.unavailable(FIRST_EPOCH, initialGeneration);
        assertEquals(EphemeralTransport.STOMP, currentTransport());
        int eventsBeforeFallback = typing.size();
        firebase.emitTyping(new TypingEvent("conversation-1", "peer-stale", null));
        chatEvents.onNext(new ChatEvent.UserTyping("conversation-1", "peer-3", "Minh"));
        assertEquals(eventsBeforeFallback + 1, typing.size());
        assertEquals("peer-3", typing.get(typing.size() - 1).getUserId());
    }

    @Test
    public void staleEpochAndGenerationCallbacksCannotSelectOrPublish() {
        createRouter(true);
        router.typingEvents().subscribe(typing::add);
        router.onAppForeground();
        long oldGeneration = firebase.generation;

        currentEpoch.set(FIRST_EPOCH + 1L);
        sessionSignals.onNext(SessionEvents.Event.SessionAvailable.INSTANCE);
        long newGeneration = firebase.generation;
        assertTrue(newGeneration > oldGeneration);
        firebase.ready(FIRST_EPOCH, oldGeneration);
        assertEquals(EphemeralTransport.STOMP, currentTransport());
        firebase.ready();
        firebase.emitTyping(FIRST_EPOCH, oldGeneration,
                new TypingEvent("conversation-1", "old-account", null));
        assertTrue(typing.isEmpty());

        firebase.emitTyping(FIRST_EPOCH + 1L, newGeneration,
                new TypingEvent("conversation-1", "new-account", null));
        assertEquals(1, typing.size());
        assertEquals("new-account", typing.get(0).getUserId());
    }

    @Test
    public void tokenRefreshRestartsFirebaseWithANewGeneration() {
        createRouter(true);
        router.onAppForeground();
        firebase.ready();
        long previousGeneration = firebase.generation;

        sessionSignals.onNext(SessionEvents.Event.SessionRefreshed.INSTANCE);

        assertTrue(firebase.generation > previousGeneration);
        assertEquals(EphemeralTransport.STOMP, currentTransport());
    }

    @Test
    public void firebaseSendFailureFallsBackToStompForTheCurrentTypingEvent() {
        createRouter(true);
        router.onAppForeground();
        firebase.ready();
        firebase.acceptTyping = false;

        assertTrue(router.sendTyping("conversation-1"));
        assertEquals(EphemeralTransport.STOMP, currentTransport());
        verify(realtimeSession).sendTyping("conversation-1");
    }

    @Test
    public void scopeUpdatesAreBoundedAndForwardedToTheActiveSource() {
        createRouter(true);
        router.onAppForeground();
        router.setPresenceObservers(Map.of("peer-1", "conversation-1"));
        router.setTypingScope("open-chat", Set.of("peer-1", "me"));

        assertEquals("open-chat", firebase.scope.getTypingConversationId());
        assertEquals(Set.of("peer-1", "me"), firebase.scope.getTypingUserIds());
        assertTrue(firebase.scope.leasedConversationIds().size()
                <= EphemeralScope.MAX_LEASED_CONVERSATIONS);
    }

    private void createRouter(boolean enabled) {
        router = new EphemeralStateRouter(tokenRefresher, sessionEvents,
                new StompEphemeralStateSource(realtimeSession, tokenRefresher), firebase, enabled);
        router.selectionChanges().subscribe(selections::add);
    }

    private EphemeralTransport currentTransport() {
        return selections.get(selections.size() - 1);
    }

    private static final class FakeSource implements EphemeralStateSource {
        private final PublishProcessor<Stamped<PresenceUpdate>> presenceEvents = PublishProcessor.create();
        private final PublishProcessor<Stamped<TypingEvent>> typingEvents = PublishProcessor.create();
        private final PublishProcessor<SourceStatus> statuses = PublishProcessor.create();
        private long epoch;
        private long generation;
        private int startCount;
        private int stopCount;
        private boolean acceptTyping = true;
        private EphemeralScope scope = EphemeralScope.empty();

        @Override public Flowable<Stamped<PresenceUpdate>> presenceUpdates() { return presenceEvents; }
        @Override public Flowable<Stamped<TypingEvent>> typingUpdates() { return typingEvents; }
        @Override public Flowable<SourceStatus> statusUpdates() { return statuses; }

        @Override
        public void start(long sessionEpoch, long generation, EphemeralScope scope) {
            this.epoch = sessionEpoch;
            this.generation = generation;
            this.scope = scope;
            startCount++;
            statuses.onNext(new SourceStatus.Starting(sessionEpoch, generation));
        }

        @Override
        public void updateScope(long sessionEpoch, long generation, EphemeralScope scope) {
            this.epoch = sessionEpoch;
            this.generation = generation;
            this.scope = scope;
            statuses.onNext(new SourceStatus.Starting(sessionEpoch, generation));
        }

        @Override public void stop() { stopCount++; }
        @Override public boolean sendTyping(String conversationId) { return acceptTyping; }
        @Override public void clearTyping(String conversationId) { }

        private void ready() { ready(epoch, generation); }
        private void ready(long epoch, long generation) {
            statuses.onNext(new SourceStatus.Ready(epoch, generation));
        }
        private void unavailable(long epoch, long generation) {
            statuses.onNext(new SourceStatus.Unavailable(epoch, generation));
        }
        private void emitTyping(TypingEvent event) { emitTyping(epoch, generation, event); }
        private void emitTyping(long epoch, long generation, TypingEvent event) {
            typingEvents.onNext(new Stamped<>(epoch, generation, event));
        }
        private void emitPresence(PresenceUpdate event) {
            presenceEvents.onNext(new Stamped<>(epoch, generation, event));
        }
    }
}

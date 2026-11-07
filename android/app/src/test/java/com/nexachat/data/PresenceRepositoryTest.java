package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Presence fan-in lifecycle: live pushes, inbox seeds (a stale seed must not
 * demote a peer already online) and the logout wipe.
 */
public final class PresenceRepositoryTest {

    private final RealtimeSession session = mock(RealtimeSession.class);
    private final SessionEvents sessionEvents = mock(SessionEvents.class);
    private final PublishProcessor<ChatEvent> chatEvents = PublishProcessor.create();
    private final BehaviorProcessor<SessionEvents.Event> sessionSignals =
            BehaviorProcessor.create();

    private PresenceRepository repository;

    @Before
    public void setUp() {
        when(session.getEvents()).thenReturn(chatEvents);
        when(sessionEvents.getEvents()).thenReturn(sessionSignals);
        repository = new PresenceRepository(session, sessionEvents);
    }

    @After
    public void tearDown() {
        repository.close();
    }

    @Test
    public void onlineAndOfflinePushesUpdateMap() {
        chatEvents.onNext(new ChatEvent.Presence("u-1", true, null));
        assertEquals(new PeerPresence(true, null), current().get("u-1"));

        chatEvents.onNext(new ChatEvent.Presence(
                "u-1", false, "2026-11-03T09:12:04Z"));
        assertEquals(new PeerPresence(false,
                TimeAndJwt.isoToEpochMsOrNull("2026-11-03T09:12:04Z")), current().get("u-1"));
    }

    @Test
    public void hiddenPresenceNeverInventsLastSeenValue() {
        chatEvents.onNext(new ChatEvent.Presence("u-1", false, null));

        assertEquals(new PeerPresence(false, null), current().get("u-1"));
    }

    @Test
    public void staleOfflineSeedNeverDemotesPeerAlreadyOnline() {
        chatEvents.onNext(new ChatEvent.Presence("u-1", true, null));

        repository.seed(Map.of("u-1", new PeerPresence(false, 5L)));

        assertEquals(new PeerPresence(true, null), current().get("u-1"));
    }

    @Test
    public void seedPromotesOfflinePeerToOnline() {
        repository.seed(Map.of("u-1", new PeerPresence(false, 5L)));
        assertEquals(new PeerPresence(false, 5L), current().get("u-1"));

        repository.seed(Map.of("u-1", new PeerPresence(true, null)));

        assertEquals(new PeerPresence(true, null), current().get("u-1"));
    }

    @Test
    public void logoutClearsEveryCachedPresence() {
        chatEvents.onNext(new ChatEvent.Presence("u-1", true, null));
        assertTrue(!current().isEmpty());

        sessionSignals.onNext(SessionEvents.Event.LoggedOut.INSTANCE);

        assertTrue(current().isEmpty());
    }

    @Test
    public void offlineEventIsNotOverwrittenByStaleInboxSeed() {
        chatEvents.onNext(new ChatEvent.Presence(
                "u-1", true, null, "2026-11-05T14:46:58.000Z"));
        chatEvents.onNext(new ChatEvent.Presence(
                "u-1", false, "2026-11-05T14:47:10.000Z", "2026-11-05T14:47:15.000Z"));
        PeerPresence offline = new PeerPresence(false,
                TimeAndJwt.isoToEpochMsOrNull("2026-11-05T14:47:10.000Z"));
        assertEquals(offline, current().get("u-1"));

        // Models an inbox request that started online and returned after the newer offline event.
        repository.seed(Map.of("u-1", new PeerPresence(true, null)));

        assertEquals(offline, current().get("u-1"));
    }

    @Test
    public void outOfOrderAndDuplicateFramesRetainNewestPresence() {
        ChatEvent.Presence online = new ChatEvent.Presence(
                "u-1", true, null, "2026-11-05T14:46:58.000Z");
        ChatEvent.Presence offline = new ChatEvent.Presence(
                "u-1", false, "2026-11-05T14:47:10.000Z", "2026-11-05T14:47:15.000Z");

        chatEvents.onNext(online);
        chatEvents.onNext(offline);
        chatEvents.onNext(online); // delayed copy of the older online state

        PeerPresence expectedOffline = new PeerPresence(false,
                TimeAndJwt.isoToEpochMsOrNull("2026-11-05T14:47:10.000Z"));
        assertEquals(expectedOffline, current().get("u-1"));

        chatEvents.onNext(offline); // exact duplicate delivery

        assertEquals(expectedOffline, current().get("u-1"));
    }

    private Map<String, PeerPresence> current() {
        return repository.getPresences().blockingFirst();
    }
}

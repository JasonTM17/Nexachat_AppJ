package com.nexachat.presence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceSessionRegistryTest {

    @Mock
    private PresenceService presenceService;
    @Mock
    private ScheduledExecutorService scheduler;
    @Mock
    private ScheduledFuture<?> offlineFuture;

    private final PresenceSessionState sessionState = new PresenceSessionState();
    private final UUID userId = UUID.randomUUID();
    private PresenceSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PresenceSessionRegistry(presenceService, sessionState, 5_000, scheduler);
        doReturn(offlineFuture).when(scheduler)
                .schedule(any(Runnable.class), eq(5_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void firstAndLastSessionEmitTransitionsOnceAndUnknownDisconnectIsIgnored() {
        Instant lastSeenAt = Instant.parse("2026-11-05T12:00:00Z");
        when(presenceService.recordLastSeen(userId)).thenReturn(lastSeenAt);

        registry.connect("session-a", userId);
        registry.connect("session-a", userId); // duplicate CONNECT event
        registry.connect("session-b", userId);
        assertThat(sessionState.isOnline(userId)).isTrue();
        verify(presenceService).connected(userId);

        registry.disconnect("unknown-session"); // lost/unmatched disconnect cannot decrement
        registry.disconnect("session-a");
        verify(presenceService, never()).recordLastSeen(userId);
        registry.disconnect("session-b");
        registry.disconnect("session-b"); // duplicate DISCONNECT event

        assertThat(sessionState.isOnline(userId)).isFalse();
        verify(presenceService).recordLastSeen(userId);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(5_000L), eq(TimeUnit.MILLISECONDS));
        task.getValue().run();
        verify(presenceService).disconnected(userId, lastSeenAt);
    }

    @Test
    void reconnectWithinWindowCancelsOfflineAndEmitsNoTransientOfflineOrSecondOnline() {
        Instant lastSeenAt = Instant.parse("2026-11-05T12:01:00Z");
        when(presenceService.recordLastSeen(userId)).thenReturn(lastSeenAt);

        registry.connect("session-a", userId);
        registry.disconnect("session-a");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(5_000L), eq(TimeUnit.MILLISECONDS));
        registry.connect("session-b", userId);
        task.getValue().run(); // deterministic scheduler: run canceled callback explicitly

        assertThat(sessionState.isOnline(userId)).isTrue();
        verify(offlineFuture).cancel(false);
        verify(presenceService).connected(userId);
        verify(presenceService, never()).disconnected(any(), any());
    }
}

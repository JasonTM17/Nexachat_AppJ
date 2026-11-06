package com.nexachat.call;

import com.nexachat.presence.PresenceSessionState;
import com.nexachat.ws.StompUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallDisconnectListenerTest {

    @Test
    void retriesDisconnectWhenTerminalPersistenceFails() {
        UUID userId = UUID.randomUUID();
        PresenceSessionState presenceState = mock(PresenceSessionState.class);
        CallTimeoutScheduler scheduler = mock(CallTimeoutScheduler.class);
        CallService callService = mock(CallService.class);
        when(presenceState.isOnline(userId)).thenReturn(false);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(callService).disconnect(userId);
        CallDisconnectListener listener = new CallDisconnectListener(presenceState, scheduler, callService);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setUser(new StompUserPrincipal(userId));
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionDisconnectEvent event = new SessionDisconnectEvent(
                this, message, "session-id", CloseStatus.NORMAL);

        listener.onSessionDisconnected(event);
        org.mockito.ArgumentCaptor<Runnable> initialCleanup = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).checkDisconnectedUser(eq(userId), initialCleanup.capture());
        initialCleanup.getValue().run();

        org.mockito.ArgumentCaptor<Runnable> retry = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).retryDisconnectedUser(retry.capture(), eq(1));
        retry.getValue().run();

        verify(callService, times(2)).disconnect(userId);
        verify(scheduler, times(1)).retryDisconnectedUser(any(Runnable.class), anyInt());
    }

    @Test
    void stopsDisconnectRetryWhenTheUserIsOnlineAgain() {
        UUID userId = UUID.randomUUID();
        PresenceSessionState presenceState = mock(PresenceSessionState.class);
        CallTimeoutScheduler scheduler = mock(CallTimeoutScheduler.class);
        CallService callService = mock(CallService.class);
        when(presenceState.isOnline(userId)).thenReturn(false, false, true);
        doThrow(new IllegalStateException("database unavailable")).when(callService).disconnect(userId);
        CallDisconnectListener listener = new CallDisconnectListener(presenceState, scheduler, callService);

        listener.onSessionDisconnected(disconnect(userId));
        org.mockito.ArgumentCaptor<Runnable> initialCleanup = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).checkDisconnectedUser(eq(userId), initialCleanup.capture());
        initialCleanup.getValue().run();

        org.mockito.ArgumentCaptor<Runnable> retry = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).retryDisconnectedUser(retry.capture(), eq(1));
        retry.getValue().run();

        verify(callService, times(1)).disconnect(userId);
        verify(scheduler, times(1)).retryDisconnectedUser(any(Runnable.class), anyInt());
    }

    @Test
    void stopsDisconnectRetryAfterFivePersistenceFailures() {
        UUID userId = UUID.randomUUID();
        PresenceSessionState presenceState = mock(PresenceSessionState.class);
        CallTimeoutScheduler scheduler = mock(CallTimeoutScheduler.class);
        CallService callService = mock(CallService.class);
        when(presenceState.isOnline(userId)).thenReturn(false);
        doThrow(new IllegalStateException("database unavailable")).when(callService).disconnect(userId);
        java.util.List<Runnable> retries = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            retries.add(invocation.getArgument(0));
            return null;
        }).when(scheduler).retryDisconnectedUser(any(Runnable.class), anyInt());
        CallDisconnectListener listener = new CallDisconnectListener(presenceState, scheduler, callService);

        listener.onSessionDisconnected(disconnect(userId));
        org.mockito.ArgumentCaptor<Runnable> initialCleanup = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).checkDisconnectedUser(eq(userId), initialCleanup.capture());
        initialCleanup.getValue().run();
        for (int index = 0; index < retries.size(); index++) {
            int before = retries.size();
            retries.get(index).run();
            if (retries.size() == before) {
                break;
            }
        }

        verify(callService, times(1 + CallTimeoutScheduler.MAX_PERSISTENCE_RETRIES)).disconnect(userId);
        verify(scheduler, times(CallTimeoutScheduler.MAX_PERSISTENCE_RETRIES))
                .retryDisconnectedUser(any(Runnable.class), anyInt());
        verify(scheduler, never()).retryDisconnectedUser(any(Runnable.class),
                eq(CallTimeoutScheduler.MAX_PERSISTENCE_RETRIES + 1));
    }

    private SessionDisconnectEvent disconnect(UUID userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setUser(new StompUserPrincipal(userId));
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, "session-id", CloseStatus.NORMAL);
    }
}

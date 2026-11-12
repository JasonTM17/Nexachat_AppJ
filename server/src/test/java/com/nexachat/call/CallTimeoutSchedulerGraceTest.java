package com.nexachat.call;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.message.CallSystemMessageWriter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallTimeoutSchedulerGraceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");

    @Test
    void checkDisconnectedUserSchedules15sGraceForActiveCall() {
        UUID userId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        CallSession activeSession = new CallSession(
                callId, conversationId, userId, peerId,
                CallState.ACTIVE, null, NOW, NOW.plusSeconds(2), null, null);

        CallRegistry registry = mock(CallRegistry.class);
        when(registry.activeCallForUser(userId)).thenReturn(Optional.of(activeSession));

        CallService service = mock(CallService.class);
        RecordingScheduler executor = new RecordingScheduler();
        CallTimeoutScheduler scheduler = new CallTimeoutScheduler(service, registry, 45_000, executor);

        scheduler.checkDisconnectedUser(userId, () -> {});

        assertThat(executor.tasks).hasSize(1);
        assertThat(executor.delays.get(0)).isEqualTo(15_000L);
    }

    @Test
    void checkDisconnectedUserSchedules50msDelayWhenNotActive() {
        UUID userId = UUID.randomUUID();

        CallRegistry registry = mock(CallRegistry.class);
        when(registry.activeCallForUser(userId)).thenReturn(Optional.empty());

        CallService service = mock(CallService.class);
        RecordingScheduler executor = new RecordingScheduler();
        CallTimeoutScheduler scheduler = new CallTimeoutScheduler(service, registry, 45_000, executor);

        scheduler.checkDisconnectedUser(userId, () -> {});

        assertThat(executor.tasks).hasSize(1);
        assertThat(executor.delays.get(0)).isEqualTo(50L);
    }

    @Test
    void fastPathSdpRetrievalReturnsOfferAndIceConfig() {
        UUID callerId = UUID.randomUUID();
        UUID calleeId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        CallSignal.Sdp sdp = new CallSignal.Sdp("offer", "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\n");
        CallSession ringingSession = CallSession.ringing(callId, conversationId, callerId, calleeId, NOW);

        CallRegistry registry = mock(CallRegistry.class);
        when(registry.find(callId)).thenReturn(Optional.of(ringingSession));
        when(registry.findOffer(callId)).thenReturn(Optional.of(sdp));

        CallService callService = new CallService(
                registry,
                mock(CallRecordRepository.class),
                mock(com.nexachat.conversation.ConversationRepository.class),
                mock(com.nexachat.conversation.ConversationMemberRepository.class),
                mock(com.nexachat.auth.RateLimitService.class),
                mock(CallSystemMessageWriter.class),
                mock(com.nexachat.user.UserRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                "stun:stun.l.google.com:19302"
        );

        CallRestController controller = new CallRestController(callService);
        JwtPrincipal calleePrincipal = new JwtPrincipal(calleeId);

        CallOfferResponse response = controller.offer(calleePrincipal, callId);

        assertThat(response).isNotNull();
        assertThat(response.callId()).isEqualTo(callId);
        assertThat(response.sdp()).isEqualTo(sdp);
        assertThat(response.iceConfig()).isNotNull();
        assertThat(response.iceConfig().iceServers()).isNotEmpty();
    }

    @Test
    void fastPathSdpRetrievalRejectsNonParticipant() {
        UUID callerId = UUID.randomUUID();
        UUID calleeId = UUID.randomUUID();
        UUID eavesdropperId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        CallSession ringingSession = CallSession.ringing(callId, conversationId, callerId, calleeId, NOW);

        CallRegistry registry = mock(CallRegistry.class);
        when(registry.find(callId)).thenReturn(Optional.of(ringingSession));

        CallService callService = new CallService(
                registry,
                mock(CallRecordRepository.class),
                mock(com.nexachat.conversation.ConversationRepository.class),
                mock(com.nexachat.conversation.ConversationMemberRepository.class),
                mock(com.nexachat.auth.RateLimitService.class),
                mock(CallSystemMessageWriter.class),
                mock(com.nexachat.user.UserRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                "stun:stun.l.google.com:19302"
        );

        CallRestController controller = new CallRestController(callService);
        JwtPrincipal eavesdropper = new JwtPrincipal(eavesdropperId);

        assertThatThrownBy(() -> controller.offer(eavesdropper, callId))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.NOT_MEMBER);
    }

    private static final class RecordingScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            delays.add(unit.toMillis(delay));
            return mock(ScheduledFuture.class);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
            delays.add(0L);
        }
    }
}

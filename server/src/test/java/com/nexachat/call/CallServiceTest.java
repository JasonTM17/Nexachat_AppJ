package com.nexachat.call;

import com.nexachat.auth.RateLimitService;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.CallSystemMessageWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallServiceTest {

    private final UUID conversationId = UUID.randomUUID();
    private final UUID callerId = UUID.randomUUID();
    private final UUID calleeId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-05T08:00:00Z");
    private CallRecordRepository recordRepository;
    private ConversationRepository conversationRepository;
    private ConversationMemberRepository memberRepository;
    private RateLimitService rateLimitService;
    private CallSystemMessageWriter systemMessageWriter;
    private ApplicationEventPublisher eventPublisher;
    private CallRegistry registry;
    private CallService service;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        registry = new CallRegistry(Clock.fixed(now, ZoneOffset.UTC), 45_000);
        recordRepository = mock(CallRecordRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        memberRepository = mock(ConversationMemberRepository.class);
        rateLimitService = mock(RateLimitService.class);
        systemMessageWriter = mock(CallSystemMessageWriter.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        conversation = mock(Conversation.class);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(conversation.getType()).thenReturn(Conversation.Type.DIRECT);
        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of(callerId, calleeId));
        when(rateLimitService.tryConsume(any(), anyLong(), any())).thenReturn(
                new RateLimitService.Consumption(true, 0));
        service = new CallService(registry, recordRepository, conversationRepository,
                memberRepository, rateLimitService, systemMessageWriter, eventPublisher,
                "stun:stun.l.google.com:19302");
    }

    @Test
    void rejectsGroupAndSelfInviteAndUnknownCall() {
        when(conversation.getType()).thenReturn(Conversation.Type.GROUP);
        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, UUID.randomUUID(), null, null)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.NOT_MEMBER);

        when(conversation.getType()).thenReturn(Conversation.Type.DIRECT);
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(calleeId, UUID.randomUUID()));
        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, UUID.randomUUID(), null, null)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.NOT_MEMBER);

        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of(callerId, callerId));
        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, UUID.randomUUID(), null, null)))
                .isInstanceOf(CallException.class);

        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.ACCEPT, UUID.randomUUID(), null, null)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.CALL_NOT_FOUND);
    }

    @Test
    void rollsBackInvitationAndReservationWhenCallRecordSaveFails() {
        UUID callId = UUID.randomUUID();
        when(recordRepository.save(any(CallRecord.class)))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.find(callId)).isEmpty();
        assertThat(registry.activeCallForUser(callerId)).isEmpty();
        assertThat(registry.activeCallForUser(calleeId)).isEmpty();
        verify(eventPublisher, never()).publishEvent(any(CallRingingEvent.class));
        verify(eventPublisher, never()).publishEvent(any(CallDelivery.class));

        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        assertThat(registry.find(callId).orElseThrow().state()).isEqualTo(CallState.RINGING);
    }

    @Test
    void rollsBackRegistryAfterThePersistenceTransactionReportsFailure() {
        UUID callId = UUID.randomUUID();
        when(recordRepository.save(any(CallRecord.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        boolean synchronizationCompleted = false;
        try {
            assertThatThrownBy(() -> service.signal(conversationId, callerId,
                    new CallSignal(CallSignal.Type.INVITE, callId, null, null)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(registry.find(callId)).isPresent();
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            synchronizationCompleted = true;

            assertThat(registry.find(callId)).isEmpty();
            assertThat(registry.activeCallForUser(callerId)).isEmpty();
            assertThat(registry.activeCallForUser(calleeId)).isEmpty();
        } finally {
            if (!synchronizationCompleted && TransactionSynchronizationManager.isSynchronizationActive()) {
                for (TransactionSynchronization synchronization
                        : TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
                }
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void restoresActiveCallAndReservationsWhenTerminalRecordSaveFails() {
        UUID callId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        service.signal(conversationId, calleeId,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        org.mockito.Mockito.clearInvocations(eventPublisher, systemMessageWriter);
        when(recordRepository.save(any(CallRecord.class)))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.END, callId, null, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.find(callId).orElseThrow().state()).isEqualTo(CallState.ACTIVE);
        assertThat(registry.activeCallForUser(callerId).orElseThrow().callId()).isEqualTo(callId);
        assertThat(registry.activeCallForUser(calleeId).orElseThrow().callId()).isEqualTo(callId);
        verify(systemMessageWriter, never()).write(any());
        verify(eventPublisher, never()).publishEvent(any(CallTerminalEvent.class));
        verify(eventPublisher, never()).publishEvent(any(CallDelivery.class));

        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.END, callId, null, null));
        assertThat(registry.find(callId).orElseThrow().state()).isEqualTo(CallState.ENDED);
        verify(systemMessageWriter, times(1)).write(any(CallSession.class));
    }

    @Test
    void rejectsOversizedSdpAndDoesNotWriteCallHistoryMessage() {
        UUID callId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        service.signal(conversationId, calleeId,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));

        CallSignal oversized = new CallSignal(CallSignal.Type.SDP, callId,
                new CallSignal.Sdp("offer", "x".repeat(16 * 1024 + 1)), null);
        assertThatThrownBy(() -> service.signal(conversationId, callerId, oversized))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.VALIDATION_FAILED);
        CallSignal oversizedUtf8 = new CallSignal(CallSignal.Type.SDP, callId,
                new CallSignal.Sdp("offer", "é".repeat(8 * 1024 + 1)), null);
        assertThatThrownBy(() -> service.signal(conversationId, callerId, oversizedUtf8))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.VALIDATION_FAILED);
        verify(systemMessageWriter, never()).write(any());
    }

    @Test
    void rejectsIceCandidateOverOneKilobyteOfUtf8() {
        UUID callId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        service.signal(conversationId, calleeId,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));

        CallSignal oversized = new CallSignal(CallSignal.Type.ICE, callId, null,
                new CallSignal.Candidate("audio", 0, "é".repeat(513)));
        assertThatThrownBy(() -> service.signal(conversationId, callerId, oversized))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.VALIDATION_FAILED);
    }

    @Test
    void racingInviteAcceptAndEndFramesWriteExactlyOneTerminalMessage() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(200);
        CountDownLatch ready = new CountDownLatch(200);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inviteWinners = new AtomicInteger();
        UUID[] winningCallId = new UUID[1];
        try {
            Future<?>[] invites = new Future<?>[200];
            for (int i = 0; i < invites.length; i++) {
                UUID callId = UUID.randomUUID();
                invites[i] = executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Invite race gate timed out");
                    }
                    try {
                        service.signal(conversationId, callerId,
                                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
                        winningCallId[0] = callId;
                        inviteWinners.incrementAndGet();
                    } catch (CallException ignored) {
                        // Only the first invite may reserve the caller and callee.
                    }
                    return null;
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> invite : invites) {
                invite.get(10, TimeUnit.SECONDS);
            }
            assertThat(inviteWinners).hasValue(1);
            UUID callId = winningCallId[0];
            assertThat(registry.activeCallForUser(callerId).orElseThrow().callId()).isEqualTo(callId);
            assertThat(registry.activeCallForUser(calleeId).orElseThrow().callId()).isEqualTo(callId);

            CountDownLatch actionReady = new CountDownLatch(200);
            CountDownLatch actionStart = new CountDownLatch(1);
            Future<?>[] actions = new Future<?>[200];
            for (int i = 0; i < actions.length; i++) {
                boolean accept = i < 100;
                actions[i] = executor.submit(() -> {
                    actionReady.countDown();
                    if (!actionStart.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Call action race gate timed out");
                    }
                    try {
                        service.signal(conversationId, accept ? calleeId : callerId,
                                new CallSignal(accept ? CallSignal.Type.ACCEPT : CallSignal.Type.END,
                                        callId, null, null));
                    } catch (CallException ignored) {
                        // Duplicate accepts and ends after the winner are rejected.
                    }
                    return null;
                });
            }
            assertThat(actionReady.await(10, TimeUnit.SECONDS)).isTrue();
            actionStart.countDown();
            for (Future<?> action : actions) {
                action.get(10, TimeUnit.SECONDS);
            }
            if (registry.find(callId).orElseThrow().state() == CallState.RINGING) {
                service.signal(conversationId, calleeId,
                        new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
            }
            if (registry.find(callId).orElseThrow().state() == CallState.ACTIVE) {
                service.signal(conversationId, callerId,
                        new CallSignal(CallSignal.Type.END, callId, null, null));
            }

            assertThat(registry.find(callId).orElseThrow().state()).isEqualTo(CallState.ENDED);
            assertThat(registry.activeCallForUser(callerId)).isEmpty();
            assertThat(registry.activeCallForUser(calleeId)).isEmpty();
            verify(systemMessageWriter, times(1)).write(any(CallSession.class));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}

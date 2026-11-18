package com.nexachat.call;

import com.nexachat.auth.RateLimitService;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.CallSystemMessageWriter;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
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
    private UserRepository userRepository;
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
        userRepository = mock(UserRepository.class);
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
        stubActiveParticipants(callerId, calleeId);
        service = new CallService(registry, recordRepository, conversationRepository,
                memberRepository, rateLimitService, systemMessageWriter,
                userRepository, eventPublisher,
                "stun:stun.l.google.com:19302",
                mock(com.nexachat.user.UserBlockRepository.class));
    }

    private void stubActiveParticipants(UUID firstUserId, UUID secondUserId) {
        when(userRepository.findActiveUsersByIdsForUpdate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<UUID> orderedIds = invocation.getArgument(0);
            return orderedIds.stream()
                    .map(userId -> userId.equals(firstUserId) || userId.equals(secondUserId)
                            ? activeUser(userId) : null)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        });
    }

    private static User activeUser(UUID userId) {
        User user = User.create("+84000000001",
                "$2a$12$abcdefghijklmnopqrstuvwxYz012345678901234567", "active");
        try {
            java.lang.reflect.Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, userId);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return user;
    }

    @Test
    void rejectsInviteWhenPeerAccountWasDeleted() {
        List<UUID> ordered = Stream.of(callerId, calleeId).sorted().toList();
        when(userRepository.findActiveUsersByIdsForUpdate(ordered)).thenReturn(List.of(activeUser(callerId)));
        UUID callId = UUID.randomUUID();
        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.NOT_MEMBER);
        assertThat(registry.find(callId)).isEmpty();
        assertThat(registry.activeCallForUser(callerId)).isEmpty();
        assertThat(registry.activeCallForUser(calleeId)).isEmpty();
        verify(recordRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(CallRingingEvent.class));
        verify(eventPublisher, never()).publishEvent(any(CallDelivery.class));
        verify(systemMessageWriter, never()).write(any());
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
    void inviteEchoesMediaTypeOnSessionDeliveryAndPersistedRecord() {
        UUID callId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null, CallMediaType.VIDEO));

        assertThat(registry.find(callId).orElseThrow().mediaType()).isEqualTo(CallMediaType.VIDEO);
        org.mockito.ArgumentCaptor<CallRecord> saved =
                org.mockito.ArgumentCaptor.forClass(CallRecord.class);
        verify(recordRepository).save(saved.capture());
        assertThat(saved.getValue().getMediaType()).isEqualTo(CallMediaType.VIDEO);
        org.mockito.ArgumentCaptor<CallDelivery> delivery =
                org.mockito.ArgumentCaptor.forClass(CallDelivery.class);
        verify(eventPublisher).publishEvent(delivery.capture());
        assertThat(delivery.getValue().recipientId()).isEqualTo(calleeId);
        assertThat(delivery.getValue().event().eventType()).isEqualTo("CALL_INVITE");
        assertThat(delivery.getValue().event().payload().mediaType()).isEqualTo("VIDEO");
    }

    @Test
    void inviteWithoutMediaTypeDefaultsToAudio() {
        UUID callId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));

        assertThat(registry.find(callId).orElseThrow().mediaType()).isEqualTo(CallMediaType.AUDIO);
        org.mockito.ArgumentCaptor<CallRecord> saved =
                org.mockito.ArgumentCaptor.forClass(CallRecord.class);
        verify(recordRepository).save(saved.capture());
        assertThat(saved.getValue().getMediaType()).isEqualTo(CallMediaType.AUDIO);
        org.mockito.ArgumentCaptor<CallDelivery> delivery =
                org.mockito.ArgumentCaptor.forClass(CallDelivery.class);
        verify(eventPublisher).publishEvent(delivery.capture());
        assertThat(delivery.getValue().event().payload().mediaType()).isEqualTo("AUDIO");
    }

    @Test
    void rejectsMediaTypeOnNonInviteFrames() {
        UUID callId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        service.signal(conversationId, calleeId,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));

        assertThatThrownBy(() -> service.signal(conversationId, calleeId,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null, CallMediaType.VIDEO)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.SDP, callId,
                        new CallSignal.Sdp("offer", "v=0"), null, CallMediaType.AUDIO)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.END, callId, null, null, CallMediaType.VIDEO)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.VALIDATION_FAILED);
        assertThat(registry.find(callId).orElseThrow().state()).isEqualTo(CallState.ACTIVE);
    }

    @Test
    void mediaTypeSurvivesAcceptAndTerminalTransitions() {
        UUID callId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null, CallMediaType.VIDEO));
        service.signal(conversationId, calleeId,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.END, callId, null, null));

        CallSession ended = registry.find(callId).orElseThrow();
        assertThat(ended.state()).isEqualTo(CallState.ENDED);
        assertThat(ended.mediaType()).isEqualTo(CallMediaType.VIDEO);
        org.mockito.ArgumentCaptor<CallSession> written =
                org.mockito.ArgumentCaptor.forClass(CallSession.class);
        verify(systemMessageWriter).write(written.capture());
        assertThat(written.getValue().mediaType()).isEqualTo(CallMediaType.VIDEO);
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
        verify(eventPublisher, never()).publishEvent(any(CallStatusProjectionEvent.class));
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
        verify(eventPublisher, never()).publishEvent(any(CallStatusProjectionEvent.class));
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
    void publishesPersistedCallStatusSnapshotsInTransitionOrder() {
        UUID callId = UUID.randomUUID();

        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        service.signal(conversationId, calleeId,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.END, callId, null, null));

        org.mockito.ArgumentCaptor<CallStatusProjectionEvent> captured =
                org.mockito.ArgumentCaptor.forClass(CallStatusProjectionEvent.class);
        verify(eventPublisher, times(3)).publishEvent(captured.capture());
        assertThat(captured.getAllValues())
                .extracting(event -> event.session().state())
                .containsExactly(CallState.RINGING, CallState.ACTIVE, CallState.ENDED);
        assertThat(captured.getAllValues())
                .extracting(event -> event.session().callId())
                .containsOnly(callId);
    }

    @Test
    void timeoutPersistsAnEndedProjectionSnapshot() {
        java.util.concurrent.atomic.AtomicReference<Instant> current =
                new java.util.concurrent.atomic.AtomicReference<>(now);
        Clock advancingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return current.get();
            }
        };
        registry = new CallRegistry(advancingClock, 45_000);
        stubActiveParticipants(callerId, calleeId);
        service = new CallService(registry, recordRepository, conversationRepository,
                memberRepository, rateLimitService, systemMessageWriter,
                userRepository, eventPublisher,
                "stun:stun.l.google.com:19302",
                mock(com.nexachat.user.UserBlockRepository.class));
        UUID callId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));

        current.set(now.plusSeconds(46));
        service.timeout(callId);

        org.mockito.ArgumentCaptor<CallStatusProjectionEvent> captured =
                org.mockito.ArgumentCaptor.forClass(CallStatusProjectionEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captured.capture());
        assertThat(captured.getAllValues())
                .extracting(event -> event.session().state())
                .containsExactly(CallState.RINGING, CallState.ENDED);
        assertThat(captured.getAllValues().get(1).session().endReason()).isEqualTo(CallEndReason.MISSED);
    }

    private UUID establishActiveCall(UUID conversation, UUID caller, UUID callee) {
        UUID callId = UUID.randomUUID();
        service.signal(conversation, caller,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        service.signal(conversation, callee,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        assertThat(registry.find(callId).orElseThrow().state()).isEqualTo(CallState.ACTIVE);
        return callId;
    }

    private UUID directConversationWith(UUID first, UUID second) {
        UUID otherConversationId = UUID.randomUUID();
        Conversation other = mock(Conversation.class);
        when(conversationRepository.findById(otherConversationId)).thenReturn(Optional.of(other));
        when(other.getType()).thenReturn(Conversation.Type.DIRECT);
        when(memberRepository.findMemberUserIds(otherConversationId)).thenReturn(List.of(first, second));
        return otherConversationId;
    }

    private void stubActiveUsers(UUID... activeIds) {
        List<UUID> active = List.of(activeIds);
        when(userRepository.findActiveUsersByIdsForUpdate(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> {
                    List<UUID> requested = invocation.getArgument(0);
                    return requested.stream().filter(active::contains)
                            .map(CallServiceTest::activeUser).toList();
                });
    }

    private List<CallDelivery> deliveries() {
        org.mockito.ArgumentCaptor<CallDelivery> captured =
                org.mockito.ArgumentCaptor.forClass(CallDelivery.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(0)).publishEvent(captured.capture());
        return captured.getAllValues();
    }

    private static List<String> summary(List<CallDelivery> sent, UUID callId) {
        return sent.stream().filter(d -> d.event().callId().equals(callId))
                .map(d -> d.recipientId() + ":" + d.event().eventType()).toList();
    }

    @Test
    void inviteFromCallerOfStaleActiveCallEndsItAndRingsTheSameCallee() {
        UUID staleId = establishActiveCall(conversationId, callerId, calleeId);
        org.mockito.Mockito.clearInvocations(eventPublisher, systemMessageWriter, recordRepository);
        UUID freshId = UUID.randomUUID();

        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, freshId, null, null));

        CallSession stale = registry.find(staleId).orElseThrow();
        assertThat(stale.state()).isEqualTo(CallState.ENDED);
        assertThat(stale.endReason()).isEqualTo(CallEndReason.FAILED);
        assertThat(registry.find(freshId).orElseThrow().state()).isEqualTo(CallState.RINGING);
        assertThat(registry.activeCallForUser(callerId).orElseThrow().callId()).isEqualTo(freshId);
        assertThat(registry.activeCallForUser(calleeId).orElseThrow().callId()).isEqualTo(freshId);

        org.mockito.ArgumentCaptor<CallRecord> saved = org.mockito.ArgumentCaptor.forClass(CallRecord.class);
        verify(recordRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(CallRecord::getCallId)
                .containsExactly(staleId, freshId);
        assertThat(saved.getAllValues().get(0).getState()).isEqualTo(CallState.ENDED);
        assertThat(saved.getAllValues().get(0).getEndReason()).isEqualTo(CallEndReason.FAILED);
        verify(systemMessageWriter, times(1)).write(any(CallSession.class));
        verify(systemMessageWriter).write(stale);

        List<CallDelivery> sent = deliveries();
        assertThat(summary(sent, staleId))
                .containsExactlyInAnyOrder(callerId + ":CALL_ENDED", calleeId + ":CALL_ENDED");
        assertThat(summary(sent, freshId)).containsExactly(calleeId + ":CALL_INVITE");
        verify(eventPublisher, times(1)).publishEvent(any(CallRingingEvent.class));
    }

    @Test
    void inviteToAThirdUserEndsTheStaleCallAndNotifiesItsPeer() {
        UUID thirdId = UUID.randomUUID();
        UUID thirdConversation = directConversationWith(callerId, thirdId);
        stubActiveUsers(callerId, calleeId, thirdId);
        UUID staleId = establishActiveCall(conversationId, callerId, calleeId);
        org.mockito.Mockito.clearInvocations(eventPublisher, systemMessageWriter, recordRepository);
        UUID freshId = UUID.randomUUID();

        service.signal(thirdConversation, callerId,
                new CallSignal(CallSignal.Type.INVITE, freshId, null, null));

        CallSession stale = registry.find(staleId).orElseThrow();
        assertThat(stale.state()).isEqualTo(CallState.ENDED);
        assertThat(stale.endReason()).isEqualTo(CallEndReason.FAILED);
        assertThat(registry.find(freshId).orElseThrow().state()).isEqualTo(CallState.RINGING);
        assertThat(registry.activeCallForUser(callerId).orElseThrow().callId()).isEqualTo(freshId);
        assertThat(registry.activeCallForUser(thirdId).orElseThrow().callId()).isEqualTo(freshId);
        assertThat(registry.activeCallForUser(calleeId)).isEmpty();
        List<CallDelivery> sent = deliveries();
        assertThat(summary(sent, staleId))
                .containsExactlyInAnyOrder(callerId + ":CALL_ENDED", calleeId + ":CALL_ENDED");
        assertThat(summary(sent, freshId)).containsExactly(thirdId + ":CALL_INVITE");
        verify(systemMessageWriter, times(1)).write(stale);
    }

    @Test
    void inviteWhileTheCallersOwnCallIsStillRingingIsRefusedUnchanged() {
        UUID ringingId = UUID.randomUUID();
        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, ringingId, null, null));
        UUID secondId = UUID.randomUUID();

        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, secondId, null, null)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.CALL_STATE_INVALID);

        assertThat(registry.find(ringingId).orElseThrow().state()).isEqualTo(CallState.RINGING);
        assertThat(registry.find(secondId)).isEmpty();
        verify(systemMessageWriter, never()).write(any());
    }

    @Test
    void inviteToACalleeInAnotherActiveCallStillAnswersBusyAndKeepsThatCall() {
        UUID thirdId = UUID.randomUUID();
        UUID thirdConversation = directConversationWith(calleeId, thirdId);
        stubActiveUsers(callerId, calleeId, thirdId);
        UUID otherCallId = establishActiveCall(thirdConversation, thirdId, calleeId);
        org.mockito.Mockito.clearInvocations(eventPublisher, systemMessageWriter, recordRepository);
        UUID busyId = UUID.randomUUID();

        service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, busyId, null, null));

        CallSession busy = registry.find(busyId).orElseThrow();
        assertThat(busy.state()).isEqualTo(CallState.ENDED);
        assertThat(busy.endReason()).isEqualTo(CallEndReason.BUSY);
        assertThat(registry.find(otherCallId).orElseThrow().state()).isEqualTo(CallState.ACTIVE);
        assertThat(registry.activeCallForUser(calleeId).orElseThrow().callId()).isEqualTo(otherCallId);
        assertThat(registry.activeCallForUser(thirdId).orElseThrow().callId()).isEqualTo(otherCallId);
        assertThat(summary(deliveries(), busyId)).containsExactly(callerId + ":CALL_BUSY");
        assertThat(summary(deliveries(), otherCallId)).isEmpty();
    }

    @Test
    void restoresTheStaleActiveCallWhenPersistingTheNewInviteFails() {
        UUID staleId = establishActiveCall(conversationId, callerId, calleeId);
        CallSession before = registry.find(staleId).orElseThrow();
        org.mockito.Mockito.clearInvocations(eventPublisher, systemMessageWriter, recordRepository);
        UUID freshId = UUID.randomUUID();
        when(recordRepository.save(any(CallRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.signal(conversationId, callerId,
                new CallSignal(CallSignal.Type.INVITE, freshId, null, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.find(freshId)).isEmpty();
        assertThat(registry.find(staleId).orElseThrow()).isSameAs(before);
        assertThat(registry.find(staleId).orElseThrow().state()).isEqualTo(CallState.ACTIVE);
        assertThat(registry.activeCallForUser(callerId).orElseThrow().callId()).isEqualTo(staleId);
        assertThat(registry.activeCallForUser(calleeId).orElseThrow().callId()).isEqualTo(staleId);
    }

    @Test
    void restoresTheStaleActiveCallWhenTheTransactionRollsBackAfterTheInvite() {
        UUID staleId = establishActiveCall(conversationId, callerId, calleeId);
        UUID freshId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.signal(conversationId, callerId,
                    new CallSignal(CallSignal.Type.INVITE, freshId, null, null));
            assertThat(registry.find(staleId).orElseThrow().state()).isEqualTo(CallState.ENDED);
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(registry.find(freshId)).isEmpty();
        assertThat(registry.find(staleId).orElseThrow().state()).isEqualTo(CallState.ACTIVE);
        assertThat(registry.activeCallForUser(callerId).orElseThrow().callId()).isEqualTo(staleId);
        assertThat(registry.activeCallForUser(calleeId).orElseThrow().callId()).isEqualTo(staleId);
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

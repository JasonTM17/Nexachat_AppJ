package com.nexachat.call;

import com.nexachat.auth.RateLimitService;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.call.FirebaseCallStatusProjectionService;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.message.CallSystemMessageWriter;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirebaseCallStatusProjectionServiceTest {

    private static final Instant START = Instant.parse("2026-11-05T08:00:00Z");
    private static final UUID CONVERSATION_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID CALLER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CALLEE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void rejectsRingTimeoutAboveTheClientValidatorLeaseLimit() {
        assertThatThrownBy(() -> new FirebaseCallStatusProjectionService(
                new RecordingRtdbClient(), mock(CallService.class), Clock.systemUTC(), 45_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("45000");
    }

    @Test
    void projectsPersistedTransitionsWithBoundedExpiryAndNoSignalingFields() {
        AdjustableClock clock = new AdjustableClock(START);
        List<CallStatusProjectionEvent> events = new ArrayList<>();
        CallService calls = callService(clock, event -> {
            if (event instanceof CallStatusProjectionEvent status) events.add(status);
        });
        RecordingRtdbClient firebase = new RecordingRtdbClient();
        FirebaseCallStatusProjectionService projection =
                new FirebaseCallStatusProjectionService(firebase, calls, clock, 45_000);
        UUID callId = UUID.randomUUID();

        calls.signal(CONVERSATION_ID, CALLER_ID,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        clock.advance(Duration.ofSeconds(10));
        calls.signal(CONVERSATION_ID, CALLEE_ID,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        clock.advance(Duration.ofSeconds(8));
        calls.signal(CONVERSATION_ID, CALLER_ID,
                new CallSignal(CallSignal.Type.END, callId, null, null));

        events.forEach(projection::onCallStatusChanged);

        assertThat(firebase.writes).hasSize(3);
        assertThat(firebase.writes)
                .extracting(update -> (String) ((Map<?, ?>) update.get("calls/" + callId)).get("state"))
                .containsExactly("RINGING", "ACTIVE", "ENDED");
        Map<?, ?> ringing = (Map<?, ?>) firebase.writes.get(0).get("calls/" + callId);
        Map<?, ?> active = (Map<?, ?>) firebase.writes.get(1).get("calls/" + callId);
        Map<?, ?> ended = (Map<?, ?>) firebase.writes.get(2).get("calls/" + callId);
        assertThat(ringing.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder(
                "cid", "callerId", "calleeId", "state", "updatedAt", "expiresAt");
        assertThat(ringing.get("cid")).isEqualTo(CONVERSATION_ID.toString());
        assertThat(ringing.get("callerId")).isEqualTo(CALLER_ID.toString());
        assertThat(ringing.get("calleeId")).isEqualTo(CALLEE_ID.toString());
        assertThat(((Number) ringing.get("expiresAt")).longValue())
                .isEqualTo(START.plusSeconds(45).toEpochMilli());
        assertThat(((Number) active.get("expiresAt")).longValue())
                .isEqualTo(START.plusSeconds(10).plusSeconds(120).toEpochMilli());
        assertThat(((Number) ended.get("expiresAt")).longValue())
                .isEqualTo(START.plusSeconds(18).plusSeconds(60).toEpochMilli());
        assertThat(ringing.keySet().stream().map(Object::toString).toList())
                .doesNotContain("sdp", "candidate", "offer", "answer");
    }

    @Test
    void timeoutPublishesEndedStateAndExpiresTheRingingProjection() {
        AdjustableClock clock = new AdjustableClock(START);
        List<CallStatusProjectionEvent> events = new ArrayList<>();
        CallService calls = callService(clock, event -> {
            if (event instanceof CallStatusProjectionEvent status) events.add(status);
        });
        RecordingRtdbClient firebase = new RecordingRtdbClient();
        FirebaseCallStatusProjectionService projection =
                new FirebaseCallStatusProjectionService(firebase, calls, clock, 45_000);
        UUID callId = UUID.randomUUID();

        calls.signal(CONVERSATION_ID, CALLER_ID,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        clock.advance(Duration.ofSeconds(46));
        calls.timeout(callId);
        events.forEach(projection::onCallStatusChanged);

        assertThat(firebase.writes).hasSize(2);
        assertThat(firebase.writes.get(0).get("calls/" + callId)).isNull();
        Map<?, ?> terminal = (Map<?, ?>) firebase.writes.get(1).get("calls/" + callId);
        assertThat(terminal.get("state")).isEqualTo("ENDED");
        assertThat(((Number) terminal.get("expiresAt")).longValue())
                .isEqualTo(START.plusSeconds(106).toEpochMilli());
    }

    @Test
    void renewsActiveLeaseButARecreatedCallServiceCannotRenewLostInMemoryState() {
        AdjustableClock clock = new AdjustableClock(START);
        List<CallStatusProjectionEvent> events = new ArrayList<>();
        CallService calls = callService(clock, event -> {
            if (event instanceof CallStatusProjectionEvent status) events.add(status);
        });
        RecordingRtdbClient firebase = new RecordingRtdbClient();
        FirebaseCallStatusProjectionService projection =
                new FirebaseCallStatusProjectionService(firebase, calls, clock, 45_000);
        UUID callId = UUID.randomUUID();
        calls.signal(CONVERSATION_ID, CALLER_ID,
                new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        calls.signal(CONVERSATION_ID, CALLEE_ID,
                new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        projection.onCallStatusChanged(events.get(1));
        long originalExpiry = ((Number) ((Map<?, ?>) firebase.writes.get(0).get("calls/" + callId))
                .get("expiresAt")).longValue();

        clock.advance(Duration.ofSeconds(61));
        projection.renewActiveCallProjections();
        long renewedExpiry = ((Number) ((Map<?, ?>) firebase.writes.get(1).get("calls/" + callId))
                .get("expiresAt")).longValue();
        assertThat(renewedExpiry).isEqualTo(clock.currentMillis() + 120_000);

        CallService restarted = callService(clock, ignored -> { });
        FirebaseCallStatusProjectionService afterRestart =
                new FirebaseCallStatusProjectionService(firebase, restarted, clock, 45_000);
        int writesBeforeRestartRenewal = firebase.writes.size();
        afterRestart.renewActiveCallProjections();

        assertThat(firebase.writes).hasSize(writesBeforeRestartRenewal);
        assertThat(originalExpiry).isLessThan(renewedExpiry);
    }

    @Test
    void deletesAStatusWhenItsLeaseHasAlreadyExpired() {
        AdjustableClock clock = new AdjustableClock(START);
        List<CallStatusProjectionEvent> events = new ArrayList<>();
        CallService calls = callService(clock, event -> {
            if (event instanceof CallStatusProjectionEvent status) events.add(status);
        });
        RecordingRtdbClient firebase = new RecordingRtdbClient();
        FirebaseCallStatusProjectionService projection =
                new FirebaseCallStatusProjectionService(firebase, calls, clock, 45_000);
        CallSession oldTerminal = new CallSession(
                UUID.randomUUID(), CONVERSATION_ID, CALLER_ID, CALLEE_ID,
                CallState.ENDED, com.nexachat.call.CallEndReason.MISSED,
                START, null, START.plusSeconds(60), 0L);

        clock.advance(Duration.ofSeconds(121));
        projection.onCallStatusChanged(new CallStatusProjectionEvent(oldTerminal));

        assertThat(firebase.writes).singleElement()
                .satisfies(update -> assertThat(update.get("calls/" + oldTerminal.callId())).isNull());
    }

    private static CallService callService(Clock clock, ApplicationEventPublisher events) {
        CallRegistry registry = new CallRegistry(clock, 45_000);
        CallRecordRepository records = mock(CallRecordRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        RateLimitService rateLimits = mock(RateLimitService.class);
        CallSystemMessageWriter systemMessages = mock(CallSystemMessageWriter.class);
        Conversation directConversation = mock(Conversation.class);
        when(conversations.findById(CONVERSATION_ID)).thenReturn(Optional.of(directConversation));
        when(directConversation.getType()).thenReturn(Conversation.Type.DIRECT);
        when(members.findMemberUserIds(CONVERSATION_ID)).thenReturn(List.of(CALLER_ID, CALLEE_ID));
        when(rateLimits.tryConsume(any(), anyLong(), any()))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        when(records.save(any(CallRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new CallService(registry, records, conversations, members, rateLimits,
                systemMessages, mock(com.nexachat.user.UserRepository.class), events,
                "stun:stun.l.google.com:19302");
    }

    private static final class RecordingRtdbClient implements FirebaseRtdbClient {
        private final List<Map<String, Object>> writes = new ArrayList<>();

        @Override
        public void applyCallStatusProjection(Map<String, Object> updates) {
            writes.add(new LinkedHashMap<>(updates));
        }

        @Override public boolean isAdministrativelyDisabled() { return false; }
        @Override public Long readSessionLease(UUID userId, UUID bridgeId) { return null; }
        @Override public void writeSessionLease(UUID userId, UUID bridgeId, long until,
                                                Map<UUID, Long> conversationLeases) { }
        @Override public void deleteBridge(UUID userId, UUID bridgeId) { }
        @Override public void revokeUser(UUID userId, List<UUID> conversationIds) { }
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        private AdjustableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        private void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        private long currentMillis() {
            return current.get().toEpochMilli();
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}

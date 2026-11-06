package com.nexachat.call;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallRegistryTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID CALLEE = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-11-05T08:00:00Z");

    @Test
    void enforcesParticipantAndRingingActionRules() {
        CallRegistry registry = registry(45_000);
        UUID callId = UUID.randomUUID();
        registry.invite(callId, CONVERSATION, CALLER, CALLEE, START);

        assertThatThrownBy(() -> registry.accept(callId, CONVERSATION, CALLER, START.plusSeconds(1)))
                .isInstanceOf(CallException.class);
        assertThatThrownBy(() -> registry.end(callId, CONVERSATION, UUID.randomUUID(), START.plusSeconds(1)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.CALL_NOT_FOUND);

        registry.accept(callId, CONVERSATION, CALLEE, START.plusSeconds(1));
        assertThatThrownBy(() -> registry.cancel(callId, CONVERSATION, CALLER, START.plusSeconds(2)))
                .isInstanceOf(CallException.class);
        assertThatThrownBy(() -> registry.accept(callId, CONVERSATION, CALLEE, START.plusSeconds(2)))
                .isInstanceOf(CallException.class);
    }

    @Test
    void ringTimeoutAndBusyInvitesAreTerminalAndReleaseReservations() {
        CallRegistry registry = registry(1000);
        UUID callId = UUID.randomUUID();
        registry.invite(callId, CONVERSATION, CALLER, CALLEE, START);

        assertThat(registry.timeout(callId, START.plusMillis(999))).isEmpty();
        var timeoutResult = registry.timeout(callId, START.plusSeconds(1));
        assertThat(timeoutResult).isPresent();
        CallRegistry.Transition timedOut = timeoutResult.orElseThrow();
        assertThat(timedOut.session().endReason()).isEqualTo(CallEndReason.MISSED);
        assertThat(registry.activeCallForUser(CALLER)).isEmpty();
        assertThat(registry.activeCallForUser(CALLEE)).isEmpty();

        UUID activeCallId = UUID.randomUUID();
        registry.invite(activeCallId, CONVERSATION, CALLER, CALLEE, START.plusSeconds(2));
        CallRegistry.Invitation busy = registry.invite(UUID.randomUUID(), CONVERSATION,
                UUID.randomUUID(), CALLEE, START.plusSeconds(2));
        assertThat(busy.busy()).isTrue();
        assertThat(busy.session().endReason()).isEqualTo(CallEndReason.BUSY);
        assertThat(registry.activeCallForUser(CALLEE).orElseThrow().callId()).isEqualTo(activeCallId);
    }

    @Test
    void endedAndUnknownCallsAreNotFoundAndEndIsSingleWinner() {
        CallRegistry registry = registry(45_000);
        UUID callId = UUID.randomUUID();
        registry.invite(callId, CONVERSATION, CALLER, CALLEE, START);
        registry.accept(callId, CONVERSATION, CALLEE, START.plusSeconds(1));
        CallRegistry.Transition terminal = registry.end(callId, CONVERSATION, CALLER, START.plusSeconds(2));
        assertThat(terminal.session().state()).isEqualTo(CallState.ENDED);
        assertThatThrownBy(() -> registry.end(callId, CONVERSATION, CALLER, START.plusSeconds(3)))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.CALL_NOT_FOUND);
    }

    @Test
    void evictsTerminalCallsAfterFiveMinutesOfRegistryTime() {
        MutableClock clock = new MutableClock(START);
        CallRegistry registry = new CallRegistry(clock, 45_000);
        UUID callId = UUID.randomUUID();
        registry.invite(callId, CONVERSATION, CALLER, CALLEE, START);
        registry.accept(callId, CONVERSATION, CALLEE, START);
        registry.end(callId, CONVERSATION, CALLER, START);

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(registry.find(callId)).isPresent();

        clock.advance(Duration.ofSeconds(1));
        assertThat(registry.find(callId)).isEmpty();
        assertThat(registry.activeCallForUser(CALLER)).isEmpty();
        assertThat(registry.activeCallForUser(CALLEE)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(new AtomicReference<>(instant), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }
    }

    private static CallRegistry registry(long timeoutMillis) {
        return new CallRegistry(Clock.fixed(START, ZoneOffset.UTC), timeoutMillis);
    }
}

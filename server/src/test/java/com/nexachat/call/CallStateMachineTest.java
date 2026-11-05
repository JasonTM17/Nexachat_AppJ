package com.nexachat.call;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallStateMachineTest {

    private static final Instant START = Instant.parse("2026-11-05T08:00:00Z");
    private static final CallSession RINGING = CallSession.ringing(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), START);

    @Test
    void acceptsThenCompletesAndMeasuresFromAnswerTime() {
        CallSession active = CallStateMachine.accept(RINGING, START.plusSeconds(3));
        CallSession ended = CallStateMachine.end(active, CallEndReason.COMPLETED, START.plusSeconds(8));
        assertThat(active.state()).isEqualTo(CallState.ACTIVE);
        assertThat(ended.state()).isEqualTo(CallState.ENDED);
        assertThat(ended.endReason()).isEqualTo(CallEndReason.COMPLETED);
        assertThat(ended.durationSeconds()).isEqualTo(5L);
    }

    @Test
    void allowsOnlyRingingTerminalReasonsBeforeAnswer() {
        for (CallEndReason reason : new CallEndReason[]{CallEndReason.REJECTED, CallEndReason.CANCELLED,
                CallEndReason.MISSED, CallEndReason.BUSY, CallEndReason.FAILED}) {
            CallSession ended = CallStateMachine.end(RINGING, reason, START.plusSeconds(1));
            assertThat(ended.endReason()).isEqualTo(reason);
            assertThat(ended.durationSeconds()).isZero();
        }
        assertThatThrownBy(() -> CallStateMachine.end(RINGING, CallEndReason.COMPLETED, START.plusSeconds(1)))
                .isInstanceOf(CallException.class);
    }

    @Test
    void rejectsIllegalTransitionsAndActiveCallReasons() {
        CallSession active = CallStateMachine.accept(RINGING, START.plusSeconds(1));
        assertThatThrownBy(() -> CallStateMachine.accept(active, START.plusSeconds(2)))
                .isInstanceOf(CallException.class);
        for (CallEndReason reason : new CallEndReason[]{CallEndReason.REJECTED, CallEndReason.CANCELLED,
                CallEndReason.MISSED, CallEndReason.BUSY}) {
            assertThatThrownBy(() -> CallStateMachine.end(active, reason, START.plusSeconds(2)))
                    .isInstanceOf(CallException.class);
        }
        CallSession ended = CallStateMachine.end(active, CallEndReason.COMPLETED, START.plusSeconds(2));
        assertThatThrownBy(() -> CallStateMachine.accept(ended, START.plusSeconds(3)))
                .isInstanceOf(CallException.class);
        assertThatThrownBy(() -> CallStateMachine.end(ended, CallEndReason.FAILED, START.plusSeconds(3)))
                .isInstanceOf(CallException.class);
    }

    @Test
    void allowsAnActiveCallToFailWhenTransportDisconnects() {
        CallSession active = CallStateMachine.accept(RINGING, START.plusSeconds(1));
        CallSession ended = CallStateMachine.end(active, CallEndReason.FAILED, START.plusSeconds(2));

        assertThat(ended.state()).isEqualTo(CallState.ENDED);
        assertThat(ended.endReason()).isEqualTo(CallEndReason.FAILED);
    }
}

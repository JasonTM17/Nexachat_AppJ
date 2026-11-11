package com.nexachat.firebase.auth;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.projection.FirebaseProjectionMetrics;
import com.nexachat.firebase.projection.FirebaseProjectionRetryQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class FirebaseSessionRevocationListenerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONVERSATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void retriesRealtimeAndAdminRevocationFailuresUntilBothSucceed(CapturedOutput output) {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-11T12:00:00Z"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FirebaseProjectionRetryQueue retries = new FirebaseProjectionRetryQueue(
                clock, new FirebaseProjectionMetrics(meterRegistry));
        FirebaseBridgeLock locks = new FirebaseBridgeLock();
        FirebaseRtdbClient rtdb = mock(FirebaseRtdbClient.class);
        FirebaseAdminClient admin = mock(FirebaseAdminClient.class);
        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        when(members.findConversationIdsByUserId(USER_ID)).thenReturn(List.of(CONVERSATION_ID));
        doThrow(new IllegalStateException("provider response secret"))
                .doNothing()
                .when(rtdb).revokeUser(USER_ID, List.of(CONVERSATION_ID));
        doThrow(new IllegalStateException("provider response secret"))
                .doNothing()
                .when(admin).revokeRefreshTokens(USER_ID.toString());
        FirebaseSessionRevocationListener listener = new FirebaseSessionRevocationListener(
                locks, rtdb, admin, members, retries);

        listener.onAccessTokensRevoked(new AccessTokensRevokedEvent(USER_ID));
        assertThat(meterRegistry.get("nexachat.firebase.projection.retry.backlog").gauge().value())
                .isEqualTo(1.0);

        clock.advance(Duration.ofSeconds(2));
        retries.retryDue();
        assertThat(meterRegistry.get("nexachat.firebase.projection.retry.backlog").gauge().value())
                .isEqualTo(1.0);

        clock.advance(Duration.ofSeconds(10));
        retries.retryDue();
        assertThat(meterRegistry.get("nexachat.firebase.projection.retry.backlog").gauge().value())
                .isZero();
        verify(rtdb, times(3)).revokeUser(USER_ID, List.of(CONVERSATION_ID));
        verify(admin, times(2)).revokeRefreshTokens(USER_ID.toString());
        assertThat(output.getOut()).doesNotContain("provider response secret");
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

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
            return current;
        }

        private void advance(Duration amount) {
            current = current.plus(amount);
        }
    }
}

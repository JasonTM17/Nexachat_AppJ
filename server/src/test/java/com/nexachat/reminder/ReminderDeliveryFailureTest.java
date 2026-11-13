package com.nexachat.reminder;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.notification.DeviceTokenService;
import com.nexachat.notification.FcmGateway;
import com.nexachat.notification.PushResult;
import com.nexachat.notification.UserDeviceToken;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.observability.ObservabilityMetrics;
import com.nexachat.user.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delivery policy for one claimed occurrence: retryable push results leave
 * the claim in place, and terminal unregistered or no-device results advance.
 */
class ReminderDeliveryFailureTest {

    @Test
    void retryablePushResultDoesNotAdvanceTheOccurrence() {
        Fixture fixture = Fixture.withDevices("token-a");
        when(fixture.fcmGateway.sendDataPush(eq("token-a"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.failure("UNAVAILABLE", false)));

        assertThat(fixture.scheduler.runOnce()).isZero();

        verify(fixture.claimService, never()).advance(any(UUID.class), any(UUID.class));
    }

    @Test
    void synchronousDispatchFailureDoesNotAdvanceTheOccurrence() {
        Fixture fixture = Fixture.withDevices("token-a", "token-b");
        when(fixture.fcmGateway.sendDataPush(eq("token-a"), anyMap(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("dispatch failed"));
        when(fixture.fcmGateway.sendDataPush(eq("token-b"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id-b")));

        assertThat(fixture.scheduler.runOnce()).isZero();

        verify(fixture.fcmGateway).sendDataPush(eq("token-a"), anyMap(), eq("HIGH"), anyLong());
        verify(fixture.fcmGateway).sendDataPush(eq("token-b"), anyMap(), eq("HIGH"), anyLong());
        verify(fixture.claimService, never()).advance(any(UUID.class), any(UUID.class));
    }

    @Test
    void exceptionalFutureDoesNotAdvanceTheOccurrence() {
        Fixture fixture = Fixture.withDevices("token-a");
        CompletableFuture<PushResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("transport"));
        when(fixture.fcmGateway.sendDataPush(eq("token-a"), anyMap(), anyString(), anyLong()))
                .thenReturn(failed);

        assertThat(fixture.scheduler.runOnce()).isZero();

        verify(fixture.claimService, never()).advance(any(UUID.class), any(UUID.class));
    }

    @Test
    void timedOutPushDoesNotAdvanceTheOccurrence() {
        Fixture fixture = Fixture.withDevices(50L, "token-a");
        when(fixture.fcmGateway.sendDataPush(eq("token-a"), anyMap(), anyString(), anyLong()))
                .thenReturn(new CompletableFuture<>());

        assertThat(fixture.scheduler.runOnce()).isZero();

        verify(fixture.claimService, never()).advance(any(UUID.class), any(UUID.class));
        verify(fixture.metrics).recordPushSend("ERROR");
    }

    @Test
    void interruptedPushDoesNotAdvanceAndStillFinishesLaterTargets() throws Exception {
        Fixture fixture = Fixture.withDevices(10_000L, "token-1", "token-2");
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<PushResult> blocked = new CompletableFuture<>() {
            @Override
            public PushResult get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                waiting.countDown();
                return super.get(timeout, unit);
            }
        };
        when(fixture.fcmGateway.sendDataPush(eq("token-1"), anyMap(), anyString(), anyLong()))
                .thenReturn(blocked);
        when(fixture.fcmGateway.sendDataPush(eq("token-2"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.unregistered("UNREGISTERED")));

        Thread worker = new Thread(fixture.scheduler::runOnce, "reminder-interrupt");
        worker.start();
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        verify(fixture.deviceTokenService).unregisterToken("token-2");
        verify(fixture.deviceTokenService, never()).unregisterToken("token-1");
        verify(fixture.claimService, never()).advance(any(UUID.class), any(UUID.class));
    }

    @Test
    void successfulPushAdvancesTheOccurrence() {
        Fixture fixture = Fixture.withDevices("token-a");
        when(fixture.fcmGateway.sendDataPush(eq("token-a"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id-a")));

        assertThat(fixture.scheduler.runOnce()).isEqualTo(1);

        verify(fixture.claimService).advance(eq(fixture.reminderId), any(UUID.class));
    }

    @Test
    void unregisteredTokenAdvancesWithoutRetry() {
        Fixture fixture = Fixture.withDevices("token-a");
        when(fixture.fcmGateway.sendDataPush(eq("token-a"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.unregistered("UNREGISTERED")));

        assertThat(fixture.scheduler.runOnce()).isEqualTo(1);

        verify(fixture.deviceTokenService).unregisterToken("token-a");
        verify(fixture.claimService).advance(eq(fixture.reminderId), any(UUID.class));
    }

    @Test
    void noRegisteredDeviceAdvancesWithoutRetry() {
        Fixture fixture = Fixture.withDevices();
        when(fixture.tokenRepository.findByUserIdIn(any())).thenReturn(List.of());

        assertThat(fixture.scheduler.runOnce()).isEqualTo(1);

        verify(fixture.fcmGateway, never()).sendDataPush(anyString(), anyMap(), anyString(), anyLong());
        verify(fixture.claimService).advance(eq(fixture.reminderId), any(UUID.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrySkipsTargetsThatAlreadySucceededAndReusesOccurrenceIdentity() {
        Fixture fixture = Fixture.withDevices("token-a", "token-b");
        AtomicInteger attempts = new AtomicInteger();
        when(fixture.fcmGateway.sendDataPush(eq("token-a"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id-a")));
        when(fixture.fcmGateway.sendDataPush(eq("token-b"), anyMap(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    if (attempts.incrementAndGet() == 1) {
                        return CompletableFuture.completedFuture(PushResult.failure("UNAVAILABLE", false));
                    }
                    return CompletableFuture.completedFuture(PushResult.success("id-b"));
                });

        assertThat(fixture.scheduler.runOnce()).isZero();
        verify(fixture.claimService, never()).advance(any(UUID.class), any(UUID.class));

        ArgumentCaptor<Map<String, String>> firstPayloads = ArgumentCaptor.forClass(Map.class);
        verify(fixture.fcmGateway, times(1))
                .sendDataPush(eq("token-a"), firstPayloads.capture(), eq("HIGH"), anyLong());
        verify(fixture.fcmGateway, times(1))
                .sendDataPush(eq("token-b"), firstPayloads.capture(), eq("HIGH"), anyLong());
        String occurrence = FcmReminderPushSender.occurrenceKey(fixture.delivery);
        assertThat(firstPayloads.getAllValues())
                .allSatisfy(payload -> assertThat(payload).containsEntry("occurrence", occurrence));

        clearInvocations(fixture.fcmGateway, fixture.claimService);
        assertThat(fixture.scheduler.runOnce()).isEqualTo(1);

        verify(fixture.fcmGateway, never())
                .sendDataPush(eq("token-a"), anyMap(), anyString(), anyLong());
        ArgumentCaptor<Map<String, String>> retryPayload = ArgumentCaptor.forClass(Map.class);
        verify(fixture.fcmGateway, times(1))
                .sendDataPush(eq("token-b"), retryPayload.capture(), eq("HIGH"), anyLong());
        assertThat(retryPayload.getValue()).containsEntry("occurrence", occurrence);
        verify(fixture.claimService).advance(eq(fixture.reminderId), any(UUID.class));
    }

    private static final class Fixture {
        private final UUID reminderId = UUID.randomUUID();
        private final ReminderDelivery delivery = new ReminderDelivery(
                reminderId,
                UUID.randomUUID(),
                "Standup",
                Instant.parse("2026-12-01T02:00:00Z"),
                Instant.parse("2026-12-01T02:00:00Z"),
                ReminderRepeat.NONE,
                UUID.randomUUID());
        private final ReminderClaimService claimService = mock(ReminderClaimService.class);
        private final ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
        private final UserDeviceTokenRepository tokenRepository = mock(UserDeviceTokenRepository.class);
        private final FcmGateway fcmGateway = mock(FcmGateway.class);
        private final DeviceTokenService deviceTokenService = mock(DeviceTokenService.class);
        private final ObservabilityMetrics metrics = mock(ObservabilityMetrics.class);
        private final ReminderScheduler scheduler;

        private Fixture(long pushTimeoutMs, String... tokens) {
            FcmReminderPushSender sender = new FcmReminderPushSender(
                    tokenRepository, fcmGateway, deviceTokenService, metrics, pushTimeoutMs);
            when(claimService.claimDue(anyLong(), any(UUID.class), anyInt())).thenReturn(List.of(reminderId));
            when(claimService.findDelivery(eq(reminderId), any(UUID.class))).thenReturn(Optional.of(delivery));
            when(claimService.advance(eq(reminderId), any(UUID.class))).thenReturn(true);
            when(memberRepository.findMemberUserIds(delivery.conversationId()))
                    .thenReturn(List.of(UUID.randomUUID()));
            List<UserDeviceToken> registered = devices(tokens);
            when(tokenRepository.findByUserIdIn(any())).thenReturn(registered);
            scheduler = new ReminderScheduler(
                    claimService,
                    memberRepository,
                    sender,
                    mock(ConversationReminderRepository.class),
                    mock(TaskScheduler.class),
                    10,
                    600,
                    false,
                    1_000,
                    Clock.systemUTC());
        }

        private static Fixture withDevices(String... tokens) {
            return new Fixture(1_000L, tokens);
        }

        private static Fixture withDevices(long pushTimeoutMs, String... tokens) {
            return new Fixture(pushTimeoutMs, tokens);
        }

        private static List<UserDeviceToken> devices(String... tokens) {
            java.util.ArrayList<UserDeviceToken> devices = new java.util.ArrayList<>();
            for (String token : tokens) {
                devices.add(device(token));
            }
            return List.copyOf(devices);
        }

        private static UserDeviceToken device(String token) {
            UserDeviceToken device = mock(UserDeviceToken.class);
            User owner = mock(User.class);
            when(owner.getId()).thenReturn(UUID.randomUUID());
            when(device.getToken()).thenReturn(token);
            when(device.getUser()).thenReturn(owner);
            when(device.getUpdatedAt()).thenReturn(Instant.parse("2026-11-11T08:00:00Z"));
            return device;
        }
    }
}

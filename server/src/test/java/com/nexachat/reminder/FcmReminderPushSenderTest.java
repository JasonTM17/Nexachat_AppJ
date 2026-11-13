package com.nexachat.reminder;

import com.nexachat.notification.DeviceTokenService;
import com.nexachat.notification.FcmGateway;
import com.nexachat.notification.PushResult;
import com.nexachat.notification.UserDeviceToken;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.observability.ObservabilityMetrics;
import com.nexachat.user.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the FCM adapter's token/future pairing and shared-device
 * de-duplication. The scheduler IT covers the end-to-end path with the mock
 * gateway; this test covers a gateway that throws before returning a future.
 */
class FcmReminderPushSenderTest {

    private final UserDeviceTokenRepository tokenRepository = mock(UserDeviceTokenRepository.class);
    private final FcmGateway fcmGateway = mock(FcmGateway.class);
    private final DeviceTokenService deviceTokenService = mock(DeviceTokenService.class);
    private final ObservabilityMetrics metrics = mock(ObservabilityMetrics.class);
    private final FcmReminderPushSender sender = new FcmReminderPushSender(
            tokenRepository, fcmGateway, deviceTokenService, metrics, 1_000);

    private final ReminderDelivery delivery = new ReminderDelivery(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Họp team",
            Instant.parse("2026-12-01T02:00:00Z"),
            Instant.parse("2026-12-01T02:00:00Z"),
            ReminderRepeat.WEEKLY,
            UUID.randomUUID());

    @Test
    void aSynchronousGatewayFailureDoesNotMisalignLaterTokens() {
        UserDeviceToken first = device("token-1");
        UserDeviceToken second = device("token-2");
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(first, second));
        when(fcmGateway.sendDataPush(eq("token-1"), anyMap(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("synchronous failure"));
        when(fcmGateway.sendDataPush(eq("token-2"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        PushResult.unregistered("UNREGISTERED")));

        assertThatThrownBy(() -> sender.send(delivery, List.of(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(ReminderPushSender.RetryableDeliveryException.class);

        // The unregistered token is token-2; the failed synchronous call for
        // token-1 must not shift the token/future pairing. The failure is
        // raised only after every target has been attempted.
        verify(deviceTokenService).unregisterToken("token-2");
        verify(deviceTokenService, never()).unregisterToken("token-1");
        verify(metrics).recordPushSend("ERROR");
        verify(metrics).recordPushSend("UNREGISTERED");
    }

    @Test
    void aSharedDeviceTokenReceivesOnePush() {
        UserDeviceToken first = device("shared-token");
        UserDeviceToken second = device("shared-token");
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(first, second));
        when(fcmGateway.sendDataPush(eq("shared-token"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id")));

        sender.send(delivery, List.of(UUID.randomUUID(), UUID.randomUUID()));

        verify(fcmGateway, times(1))
                .sendDataPush(eq("shared-token"), anyMap(), eq("HIGH"), anyLong());
        verify(metrics, times(1)).recordPushSend("SUCCESS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSharedDeviceTokenIsAddressedToTheAccountSignedInLast() {
        UUID earlier = UUID.randomUUID();
        UUID latest = UUID.randomUUID();
        UserDeviceToken older = device("shared-token", earlier, Instant.parse("2026-11-10T08:00:00Z"));
        UserDeviceToken newer = device("shared-token", latest, Instant.parse("2026-11-11T08:00:00Z"));
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(older, newer));
        when(fcmGateway.sendDataPush(eq("shared-token"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id")));

        sender.send(delivery, List.of(earlier, latest));

        ArgumentCaptor<Map<String, String>> payload = ArgumentCaptor.forClass(Map.class);
        verify(fcmGateway, times(1))
                .sendDataPush(eq("shared-token"), payload.capture(), eq("HIGH"), anyLong());
        assertThat(payload.getValue()).containsEntry("recipientId", latest.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void payloadCarriesAnOpaqueOccurrenceKeyInsteadOfTheSchedule() {
        UUID recipient = UUID.randomUUID();
        UserDeviceToken device = device("token-a", recipient, null);
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(device));
        when(fcmGateway.sendDataPush(anyString(), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id")));

        sender.send(delivery, List.of(recipient));

        ArgumentCaptor<Map<String, String>> payload = ArgumentCaptor.forClass(Map.class);
        verify(fcmGateway).sendDataPush(eq("token-a"), payload.capture(), eq("HIGH"), anyLong());
        Map<String, String> data = payload.getValue();
        assertThat(data)
                .containsEntry("recipientId", recipient.toString())
                .containsEntry("reminderId", delivery.reminderId().toString())
                .containsEntry("occurrence", FcmReminderPushSender.occurrenceKey(delivery))
                .doesNotContainKey("remindAt");
        assertThat(data.get("occurrence")).matches("[0-9a-f]{32}");
        assertThat(data.values()).noneMatch(value -> value.contains("2026-12-01"));

        ReminderDelivery nextWeek = new ReminderDelivery(
                delivery.reminderId(), delivery.conversationId(), delivery.title(),
                delivery.remindAt().plusSeconds(7 * 24 * 3_600L), delivery.anchorAt(),
                delivery.repeat(), delivery.claimToken());
        assertThat(FcmReminderPushSender.occurrenceKey(nextWeek))
                .isNotEqualTo(FcmReminderPushSender.occurrenceKey(delivery));
        assertThat(FcmReminderPushSender.occurrenceKey(delivery))
                .isEqualTo(FcmReminderPushSender.occurrenceKey(delivery));
    }

    private static UserDeviceToken device(String token) {
        return device(token, UUID.randomUUID(), null);
    }

    private static UserDeviceToken device(String token, UUID ownerId, Instant updatedAt) {
        UserDeviceToken device = mock(UserDeviceToken.class);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        when(device.getToken()).thenReturn(token);
        when(device.getUser()).thenReturn(owner);
        when(device.getUpdatedAt()).thenReturn(updatedAt);
        return device;
    }
}

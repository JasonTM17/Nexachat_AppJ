package com.nexachat.reminder;

import com.nexachat.notification.DeviceTokenService;
import com.nexachat.notification.FcmGateway;
import com.nexachat.notification.PushResult;
import com.nexachat.notification.UserDeviceToken;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.observability.ObservabilityMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
        UUID firstRecipient = UUID.randomUUID();
        UUID secondRecipient = UUID.randomUUID();
        UserDeviceToken first = device("token-1", firstRecipient);
        UserDeviceToken second = device("token-2", secondRecipient);
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(first, second));
        when(fcmGateway.sendDataPush(eq("token-1"), anyMap(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("synchronous failure"));
        when(fcmGateway.sendDataPush(eq("token-2"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        PushResult.unregistered("UNREGISTERED")));

        sender.send(delivery, List.of(firstRecipient, secondRecipient));

        // The unregistered token is token-2; the failed synchronous call for
        // token-1 must not shift the token/future pairing.
        verify(deviceTokenService).unregisterToken("token-2");
        verify(deviceTokenService, never()).unregisterToken("token-1");
        verify(metrics).recordPushSend("ERROR");
        verify(metrics).recordPushSend("UNREGISTERED");
    }

    @Test
    void aSharedDeviceReceivesAnAccountBoundPushForEachRecipient() {
        UUID firstRecipient = UUID.randomUUID();
        UUID secondRecipient = UUID.randomUUID();
        UserDeviceToken first = device("shared-token", firstRecipient);
        UserDeviceToken second = device("shared-token", secondRecipient);
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(first, second));
        when(fcmGateway.sendDataPush(eq("shared-token"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id")));

        sender.send(delivery, List.of(firstRecipient, secondRecipient));

        org.mockito.ArgumentCaptor<Map<String, String>> payloads =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(fcmGateway, times(2)).sendDataPush(
                eq("shared-token"), payloads.capture(), eq("HIGH"), anyLong());
        verify(metrics, times(2)).recordPushSend("SUCCESS");
        org.assertj.core.api.Assertions.assertThat(payloads.getAllValues())
                .extracting(payload -> payload.get("recipientId"))
                .containsExactlyInAnyOrder(firstRecipient.toString(), secondRecipient.toString());
    }

    @Test
    void reminderFanOutCarriesRecipientAndOccurrenceForEachAccount() {
        UUID firstRecipient = UUID.randomUUID();
        UUID secondRecipient = UUID.randomUUID();
        UserDeviceToken first = device("token-a", firstRecipient);
        UserDeviceToken second = device("token-b", secondRecipient);
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(first, second));
        when(fcmGateway.sendDataPush(anyString(), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id")));

        sender.send(delivery, List.of(firstRecipient, secondRecipient));

        org.mockito.ArgumentCaptor<Map<String, String>> firstPayload =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.ArgumentCaptor<Map<String, String>> secondPayload =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(fcmGateway).sendDataPush(eq("token-a"), firstPayload.capture(), eq("HIGH"), anyLong());
        verify(fcmGateway).sendDataPush(eq("token-b"), secondPayload.capture(), eq("HIGH"), anyLong());
        org.assertj.core.api.Assertions.assertThat(firstPayload.getValue())
                .containsEntry("recipientId", firstRecipient.toString())
                .containsEntry("reminderId", delivery.reminderId().toString())
                .containsEntry("remindAt", delivery.remindAt().toString());
        org.assertj.core.api.Assertions.assertThat(secondPayload.getValue())
                .containsEntry("recipientId", secondRecipient.toString())
                .containsEntry("reminderId", delivery.reminderId().toString())
                .containsEntry("remindAt", delivery.remindAt().toString());
    }

    private static UserDeviceToken device(String token, UUID userId) {
        UserDeviceToken device = mock(UserDeviceToken.class);
        when(device.getToken()).thenReturn(token);
        com.nexachat.user.User user = mock(com.nexachat.user.User.class);
        when(user.getId()).thenReturn(userId);
        when(device.getUser()).thenReturn(user);
        return device;
    }
}

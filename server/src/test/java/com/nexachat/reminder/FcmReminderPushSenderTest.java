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
        UserDeviceToken first = device("token-1");
        UserDeviceToken second = device("token-2");
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(first, second));
        when(fcmGateway.sendDataPush(eq("token-1"), anyMap(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("synchronous failure"));
        when(fcmGateway.sendDataPush(eq("token-2"), anyMap(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        PushResult.unregistered("UNREGISTERED")));

        sender.send(delivery, List.of(UUID.randomUUID(), UUID.randomUUID()));

        // The unregistered token is token-2; the failed synchronous call for
        // token-1 must not shift the token/future pairing.
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

    private static UserDeviceToken device(String token) {
        UserDeviceToken device = mock(UserDeviceToken.class);
        when(device.getToken()).thenReturn(token);
        return device;
    }
}

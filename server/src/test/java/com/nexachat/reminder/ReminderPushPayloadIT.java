package com.nexachat.reminder;

import com.nexachat.notification.DeviceTokenService;
import com.nexachat.notification.FcmGateway;
import com.nexachat.notification.PushResult;
import com.nexachat.notification.UserDeviceToken;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.observability.ObservabilityMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression: reminder FCM data must not carry titles or schedules that could
 * appear on a muted lock screen.
 */
class ReminderPushPayloadIT {

    @Test
    void reminderDataPushOmitsSensitiveReminderText() {
        UserDeviceTokenRepository tokenRepository = mock(UserDeviceTokenRepository.class);
        FcmGateway fcmGateway = mock(FcmGateway.class);
        UserDeviceToken device = mock(UserDeviceToken.class);
        when(device.getToken()).thenReturn("token-1");
        when(tokenRepository.findByUserIdIn(any())).thenReturn(List.of(device));

        ArgumentCaptor<Map<String, String>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        when(fcmGateway.sendDataPush(eq("token-1"), payloadCaptor.capture(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(PushResult.success("id")));

        FcmReminderPushSender sender = new FcmReminderPushSender(
                tokenRepository,
                fcmGateway,
                mock(DeviceTokenService.class),
                mock(ObservabilityMetrics.class),
                1_000);

        ReminderDelivery delivery = new ReminderDelivery(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Board title must not leak",
                Instant.parse("2026-12-01T02:00:00Z"),
                Instant.parse("2026-12-01T02:00:00Z"),
                ReminderRepeat.WEEKLY,
                UUID.randomUUID());

        sender.send(delivery, List.of(UUID.randomUUID()));

        Map<String, String> data = payloadCaptor.getValue();
        assertThat(data.get("type")).isEqualTo("REMINDER");
        assertThat(data).containsKeys("conversationId", "reminderId", "repeat");
        assertThat(data).doesNotContainKey("title");
        assertThat(data).doesNotContainKey("remindAt");
        assertThat(data.values()).noneMatch(value -> value != null && value.contains("must not leak"));
    }
}

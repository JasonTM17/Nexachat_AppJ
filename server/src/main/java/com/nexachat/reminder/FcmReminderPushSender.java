package com.nexachat.reminder;

import com.nexachat.notification.DeviceTokenService;
import com.nexachat.notification.FcmGateway;
import com.nexachat.notification.PushResult;
import com.nexachat.notification.UserDeviceToken;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.observability.ObservabilityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FCM adapter for reminder fan-out.
 *
 * <p>The payload is data-only and pointer-sized: only ids (and optional repeat
 * metadata) travel through FCM so lock screens never show reminder titles or
 * chat content. The client loads board details after refresh.</p>
 */
@Service
public class FcmReminderPushSender implements ReminderPushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmReminderPushSender.class);
    private static final long DEFAULT_TTL_SECONDS = 3_600L;

    private final UserDeviceTokenRepository tokenRepository;
    private final FcmGateway fcmGateway;
    private final DeviceTokenService deviceTokenService;
    private final ObservabilityMetrics metrics;
    private final long pushTimeoutMs;

    public FcmReminderPushSender(UserDeviceTokenRepository tokenRepository,
                                 FcmGateway fcmGateway,
                                 DeviceTokenService deviceTokenService,
                                 ObservabilityMetrics metrics,
                                 @Value("${nexachat.reminders.push-timeout-ms:10000}") long pushTimeoutMs) {
        this.tokenRepository = tokenRepository;
        this.fcmGateway = fcmGateway;
        this.deviceTokenService = deviceTokenService;
        this.metrics = metrics;
        this.pushTimeoutMs = pushTimeoutMs;
    }

    @Override
    public void send(ReminderDelivery reminder, List<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }
        List<UserDeviceToken> devices = tokenRepository.findByUserIdIn(new HashSet<>(memberIds));
        if (devices.isEmpty()) {
            return;
        }

        Map<String, String> data = new HashMap<>();
        data.put("type", "REMINDER");
        data.put("reminderId", reminder.reminderId().toString());
        data.put("conversationId", reminder.conversationId().toString());
        data.put("repeat", reminder.repeat().name());

        // One physical device can hold a token row per account; the same token
        // must receive this reminder once.
        Set<String> dispatched = new HashSet<>();
        List<String> tokenValues = new ArrayList<>();
        List<CompletableFuture<PushResult>> futures = new ArrayList<>();
        for (UserDeviceToken device : devices) {
            String token = device.getToken();
            if (!dispatched.add(token)) {
                continue;
            }
            try {
                CompletableFuture<PushResult> future =
                        fcmGateway.sendDataPush(token, data, "HIGH", DEFAULT_TTL_SECONDS);
                tokenValues.add(token);
                futures.add(future);
            } catch (RuntimeException failure) {
                metrics.recordPushSend("ERROR");
                log.warn("Reminder {}: FCM dispatch failed ({})",
                        reminder.reminderId(), failure.getClass().getSimpleName());
            }
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, pushTimeoutMs));
        for (int i = 0; i < futures.size(); i++) {
            String token = tokenValues.get(i);
            try {
                long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
                PushResult result = futures.get(i).get(remaining, TimeUnit.NANOSECONDS);
                if (result.success()) {
                    metrics.recordPushSend("SUCCESS");
                } else if (result.unregistered()) {
                    metrics.recordPushSend("UNREGISTERED");
                    removeUnregisteredToken(token);
                } else {
                    metrics.recordPushSend("FAILURE");
                }
            } catch (TimeoutException timeout) {
                metrics.recordPushSend("ERROR");
                log.warn("Reminder {}: FCM push timed out", reminder.reminderId());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                metrics.recordPushSend("ERROR");
                return;
            } catch (ExecutionException failure) {
                metrics.recordPushSend("ERROR");
                log.warn("Reminder {}: FCM push failed ({})",
                        reminder.reminderId(), failure.getClass().getSimpleName());
            }
        }
        log.info("Reminder {} push attempted for {} device token(s)",
                reminder.reminderId(), futures.size());
    }

    private void removeUnregisteredToken(String tokenValue) {
        try {
            deviceTokenService.unregisterToken(tokenValue);
        } catch (RuntimeException ex) {
            // The exception message can echo SQL that carries the registration
            // token, so log the type only.
            log.error("Failed to unregister an expired device token: {}",
                    ex.getClass().getSimpleName());
        }
    }
}

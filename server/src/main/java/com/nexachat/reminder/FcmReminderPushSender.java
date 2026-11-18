package com.nexachat.reminder;

import com.nexachat.notification.DeviceTokenOwners;
import com.nexachat.notification.DeviceTokenService;
import com.nexachat.notification.FcmGateway;
import com.nexachat.notification.PushResult;
import com.nexachat.notification.UserDeviceToken;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.observability.ObservabilityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Bounds settled entries whose occurrence was abandoned mid-claim. */
    private static final long DEFAULT_SETTLED_TARGET_TTL_MILLIS = 3_600_000L;

    private final UserDeviceTokenRepository tokenRepository;
    private final FcmGateway fcmGateway;
    private final DeviceTokenService deviceTokenService;
    private final ObservabilityMetrics metrics;
    private final long pushTimeoutMs;
    private final long settledTargetTtlMillis;
    /**
     * Targets accepted for an occurrence that still has a retryable miss.
     * Cleared once that occurrence meets the delivery policy. A restarted
     * process does not have this set, so a target may be pushed again.
     *
     * <p>Entries carry an expiry because an occurrence can be abandoned
     * mid-claim (the reminder is deleted or rescheduled while a push result
     * was retryable), which skips the per-occurrence cleanup below. Expiring
     * the entries bounds the set; a duplicate push after expiry is still
     * suppressed by the client's occurrence ledger.</p>
     */
    private final Set<SettledTarget> settledTargets = ConcurrentHashMap.newKeySet();

    @Autowired
    public FcmReminderPushSender(UserDeviceTokenRepository tokenRepository,
                                 FcmGateway fcmGateway,
                                 DeviceTokenService deviceTokenService,
                                 ObservabilityMetrics metrics,
                                 @Value("${nexachat.reminders.push-timeout-ms:10000}") long pushTimeoutMs) {
        this(tokenRepository, fcmGateway, deviceTokenService, metrics, pushTimeoutMs,
                DEFAULT_SETTLED_TARGET_TTL_MILLIS);
    }

    FcmReminderPushSender(UserDeviceTokenRepository tokenRepository,
                          FcmGateway fcmGateway,
                          DeviceTokenService deviceTokenService,
                          ObservabilityMetrics metrics,
                          long pushTimeoutMs,
                          long settledTargetTtlMillis) {
        this.tokenRepository = tokenRepository;
        this.fcmGateway = fcmGateway;
        this.deviceTokenService = deviceTokenService;
        this.metrics = metrics;
        this.pushTimeoutMs = pushTimeoutMs;
        this.settledTargetTtlMillis = Math.max(1L, settledTargetTtlMillis);
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

        // One physical device can hold a token row per account; it receives the
        // reminder once, addressed to the account signed in there.
        String occurrence = occurrenceKey(reminder);
        List<String> tokenValues = new ArrayList<>();
        List<CompletableFuture<PushResult>> futures = new ArrayList<>();
        Map<UUID, List<UserDeviceToken>> rowsByAccount = new LinkedHashMap<>();
        for (UserDeviceToken device : devices) {
            if (device.getUser() != null && device.getUser().getId() != null) {
                rowsByAccount.computeIfAbsent(device.getUser().getId(), ignored -> new ArrayList<>())
                        .add(device);
            }
        }
        List<Map.Entry<UUID, UserDeviceToken>> targets = new ArrayList<>();
        DeviceTokenOwners.byCurrentOwner(rowsByAccount).forEach((accountId, owned) ->
                owned.forEach(device -> targets.add(Map.entry(accountId, device))));
        boolean retryable = false;
        for (Map.Entry<UUID, UserDeviceToken> target : targets) {
            String token = target.getValue().getToken();
            if (settledRecently(occurrence, token)) {
                continue;
            }
            Map<String, String> data = new HashMap<>();
            data.put("type", "REMINDER");
            data.put("recipientId", target.getKey().toString());
            data.put("reminderId", reminder.reminderId().toString());
            data.put("conversationId", reminder.conversationId().toString());
            data.put("occurrence", occurrence);
            data.put("repeat", reminder.repeat().name());
            try {
                CompletableFuture<PushResult> future =
                        fcmGateway.sendDataPush(token, data, "HIGH", DEFAULT_TTL_SECONDS);
                tokenValues.add(token);
                futures.add(future);
            } catch (RuntimeException failure) {
                metrics.recordPushSend("ERROR");
                retryable = true;
                log.warn("Reminder {}: FCM dispatch failed ({})",
                        reminder.reminderId(), failure.getClass().getSimpleName());
            }
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, pushTimeoutMs));
        boolean interrupted = false;
        for (int i = 0; i < futures.size(); i++) {
            String token = tokenValues.get(i);
            try {
                long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
                PushResult result = futures.get(i).get(remaining, TimeUnit.NANOSECONDS);
                if (result != null && result.success()) {
                    metrics.recordPushSend("SUCCESS");
                    settledTargets.add(new SettledTarget(occurrence, token, expiryMillis()));
                } else if (result != null && result.unregistered()) {
                    metrics.recordPushSend("UNREGISTERED");
                    removeUnregisteredToken(token);
                    settledTargets.add(new SettledTarget(occurrence, token, expiryMillis()));
                } else {
                    metrics.recordPushSend("FAILURE");
                    retryable = true;
                }
            } catch (TimeoutException timeout) {
                metrics.recordPushSend("ERROR");
                retryable = true;
                log.warn("Reminder {}: FCM push timed out", reminder.reminderId());
            } catch (InterruptedException interruptedWait) {
                interrupted = true;
                Thread.interrupted();
                metrics.recordPushSend("ERROR");
                retryable = true;
                log.warn("Reminder {}: FCM push interrupted", reminder.reminderId());
            } catch (ExecutionException failure) {
                metrics.recordPushSend("ERROR");
                retryable = true;
                log.warn("Reminder {}: FCM push failed ({})",
                        reminder.reminderId(), failure.getClass().getSimpleName());
            }
        }
        if (!retryable) {
            settledTargets.removeIf(settled ->
                    occurrence.equals(settled.occurrence()) || settled.isExpired());
        } else {
            settledTargets.removeIf(SettledTarget::isExpired);
        }
        log.info("Reminder {} push attempted for {} recipient target(s)",
                reminder.reminderId(), futures.size());
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (retryable) {
            throw new ReminderPushSender.RetryableDeliveryException();
        }
    }

    /**
     * True while a target accepted for this occurrence is still inside the
     * dedupe window. An expired entry is dropped here, so occurrences
     * abandoned mid-claim cannot retain their entries forever.
     */
    private boolean settledRecently(String occurrence, String token) {
        SettledTarget probe = new SettledTarget(occurrence, token, 0L);
        SettledTarget settled = settledTargets.stream()
                .filter(probe::equalsIgnoringExpiry)
                .findFirst()
                .orElse(null);
        if (settled == null) {
            return false;
        }
        if (settled.isExpired()) {
            settledTargets.remove(settled);
            return false;
        }
        return true;
    }

    private long expiryMillis() {
        return System.currentTimeMillis() + settledTargetTtlMillis;
    }

    /**
     * One accepted push for one occurrence and device token. The expiry lets
     * the entry age out when its occurrence is abandoned mid-claim and never
     * reaches the per-occurrence cleanup.
     */
    private record SettledTarget(String occurrence, String token, long expiresAtMillis) {

        private boolean isExpired() {
            return expiresAtMillis <= System.currentTimeMillis();
        }

        private boolean equalsIgnoringExpiry(SettledTarget other) {
            return occurrence.equals(other.occurrence) && token.equals(other.token);
        }
    }

    /**
     * A stable, opaque key for one firing of a reminder. The client uses it to
     * show each occurrence once; it does not carry the schedule in readable form.
     */
    static String occurrenceKey(ReminderDelivery reminder) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((reminder.reminderId() + "|" + reminder.remindAt().toEpochMilli())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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

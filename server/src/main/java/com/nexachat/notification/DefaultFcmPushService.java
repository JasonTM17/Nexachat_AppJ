package com.nexachat.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DefaultFcmPushService implements FcmPushService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFcmPushService.class);
    private static final long DEFAULT_RING_TIMEOUT_MS = 45_000L;

    private final UserDeviceTokenRepository tokenRepository;
    private final FcmGateway fcmGateway;
    private final DeviceTokenService deviceTokenService;
    private final long ringTimeoutMs;

    public DefaultFcmPushService(UserDeviceTokenRepository tokenRepository, FcmGateway fcmGateway) {
        this(tokenRepository, fcmGateway, null, DEFAULT_RING_TIMEOUT_MS);
    }

    public DefaultFcmPushService(
            UserDeviceTokenRepository tokenRepository,
            FcmGateway fcmGateway,
            DeviceTokenService deviceTokenService) {
        this(tokenRepository, fcmGateway, deviceTokenService, DEFAULT_RING_TIMEOUT_MS);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultFcmPushService(UserDeviceTokenRepository tokenRepository,
                                 FcmGateway fcmGateway,
                                 @org.springframework.context.annotation.Lazy
                                 DeviceTokenService deviceTokenService,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${nexachat.calls.ring-timeout-ms:45000}")
                                 long ringTimeoutMs) {
        if (ringTimeoutMs < 1L) {
            throw new IllegalArgumentException("ringTimeoutMs must be positive");
        }
        this.tokenRepository = tokenRepository;
        this.fcmGateway = fcmGateway;
        this.deviceTokenService = deviceTokenService;
        this.ringTimeoutMs = ringTimeoutMs;
    }

    @Override
    @Transactional
    public void sendNewMessageNotification(UUID recipientId, UUID conversationId, UUID messageId,
                                          long seq, String senderName, boolean mentioned) {
        if (recipientId == null) {
            return;
        }
        List<UserDeviceToken> tokens = tokenRepository.findByUserId(recipientId);
        if (tokens.isEmpty()) {
            return;
        }
        sendNewMessageNotification(
                recipientId, tokens, conversationId, messageId, seq, senderName, mentioned);
    }

    @Override
    @Transactional
    public void sendNewMessageNotification(List<UserDeviceToken> tokens, UUID conversationId,
                                          UUID messageId, long seq, String senderName,
                                          boolean mentioned) {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }

        // A shared token may be registered to multiple accounts. Keep each
        // account's audience explicit so the client can reject the other one.
        Map<UUID, List<UserDeviceToken>> byRecipient = new LinkedHashMap<>();
        for (UserDeviceToken token : tokens) {
            if (token.getUser() == null || token.getUser().getId() == null) {
                continue;
            }
            byRecipient.computeIfAbsent(token.getUser().getId(), ignored -> new ArrayList<>())
                    .add(token);
        }
        byRecipient.forEach((recipientId, recipientTokens) -> sendNewMessageNotification(
                recipientId, recipientTokens, conversationId, messageId, seq, senderName, mentioned));
    }

    @Override
    @Transactional
    public void sendNewMessageNotification(
            UUID recipientId,
            List<UserDeviceToken> tokens,
            UUID conversationId,
            UUID messageId,
            long seq,
            String senderName,
            boolean mentioned) {
        if (recipientId == null || tokens == null || tokens.isEmpty()) {
            return;
        }

        // Pointer only: the payload carries ids the app can use to sync, never
        // message content, which would sit in the push provider's store.
        Map<String, String> data = new HashMap<>();
        data.put("type", "NEW_MESSAGE");
        data.put("recipientId", recipientId.toString());
        data.put("conversationId", conversationId.toString());
        data.put("messageId", messageId.toString());
        data.put("seq", String.valueOf(seq));
        data.put("senderName", senderName != null ? senderName : "");
        if (mentioned) {
            data.put("mentioned", "true");
        }

        dispatchToTokens(tokens, data, "HIGH", 86400L);
    }

    @Override
    @Transactional
    public void sendCallInviteNotification(UUID recipientId, UUID callId, UUID conversationId,
                                          UUID callerId, String callerName, String callType, long timestamp) {
        List<UserDeviceToken> tokens = tokenRepository.findByUserId(recipientId);
        if (tokens.isEmpty()) {
            return;
        }

        Map<String, String> data = new HashMap<>();
        data.put("type", "CALL_INVITE");
        data.put("callId", callId.toString());
        data.put("conversationId", conversationId.toString());
        data.put("callerId", callerId.toString());
        data.put("recipientId", recipientId.toString());
        data.put("callerName", callerName != null ? callerName : "");
        data.put("callType", callType != null ? callType : "AUDIO");
        data.put("timestamp", String.valueOf(timestamp));
        data.put("ringExpiresAt", String.valueOf(Math.addExact(timestamp, ringTimeoutMs)));

        dispatchToTokens(tokens, data, "HIGH", 60L);
    }

    private void dispatchToTokens(List<UserDeviceToken> tokens, Map<String, String> data,
                                  String priority, long ttlSeconds) {
        // A shared physical device can hold one row per account; the same
        // token must still receive a given push exactly once.
        Set<String> dispatched = new HashSet<>();
        for (UserDeviceToken token : tokens) {
            String tokenValue = token.getToken();
            if (!dispatched.add(tokenValue)) {
                continue;
            }
            fcmGateway.sendDataPush(tokenValue, data, priority, ttlSeconds)
                    .thenAccept(result -> {
                        if (result.success()) {
                            // Never log the registration token, message body, or
                            // user-supplied device label (may contain PII).
                            log.info("FCM push accepted (platform {})", token.getPlatform());
                        }
                        if (result.unregistered()) {
                            // The registration token is a credential: identify the
                            // row by platform only, never by token or device label.
                            log.warn("FCM token expired/unregistered, removing device token (platform {})",
                                    token.getPlatform());
                            try {
                                if (deviceTokenService != null) {
                                    deviceTokenService.unregisterToken(tokenValue);
                                } else {
                                    tokenRepository.deleteByToken(tokenValue);
                                }
                            } catch (Exception ex) {
                                // The exception message can echo SQL that carries the
                                // registration token, so log the type only.
                                log.error("Failed to unregister an expired device token: {}",
                                        ex.getClass().getSimpleName());
                            }
                        }
                    });
        }
    }
}

package com.nexachat.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DefaultFcmPushService implements FcmPushService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFcmPushService.class);

    private final UserDeviceTokenRepository tokenRepository;
    private final FcmGateway fcmGateway;
    private final DeviceTokenService deviceTokenService;

    public DefaultFcmPushService(UserDeviceTokenRepository tokenRepository, FcmGateway fcmGateway) {
        this(tokenRepository, fcmGateway, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultFcmPushService(UserDeviceTokenRepository tokenRepository,
                                 FcmGateway fcmGateway,
                                 @org.springframework.context.annotation.Lazy
                                 DeviceTokenService deviceTokenService) {
        this.tokenRepository = tokenRepository;
        this.fcmGateway = fcmGateway;
        this.deviceTokenService = deviceTokenService;
    }

    @Override
    @Transactional
    public void sendNewMessageNotification(UUID recipientId, UUID conversationId, UUID messageId,
                                          long seq, String senderName) {
        List<UserDeviceToken> tokens = tokenRepository.findByUserId(recipientId);
        if (tokens.isEmpty()) {
            return;
        }
        sendNewMessageNotification(tokens, conversationId, messageId, seq, senderName);
    }

    @Override
    @Transactional
    public void sendNewMessageNotification(List<UserDeviceToken> tokens, UUID conversationId,
                                          UUID messageId, long seq, String senderName) {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }

        // Pointer only: the payload carries ids the app can use to sync, never
        // message content, which would sit in the push provider's store.
        Map<String, String> data = new HashMap<>();
        data.put("type", "NEW_MESSAGE");
        data.put("conversationId", conversationId.toString());
        data.put("messageId", messageId.toString());
        data.put("seq", String.valueOf(seq));
        data.put("senderName", senderName != null ? senderName : "");

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
        data.put("callerName", callerName != null ? callerName : "");
        data.put("callType", callType != null ? callType : "AUDIO");
        data.put("timestamp", String.valueOf(timestamp));

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
                            // Never log the registration token or the message body.
                            log.info("FCM push accepted (platform {}, label {})",
                                    token.getPlatform(), token.getDeviceLabel());
                        }
                        if (result.unregistered()) {
                            // The registration token is a credential: identify the
                            // row by its platform and label, never by the token value.
                            log.warn("FCM token expired/unregistered, removing device token (platform {}, label {})",
                                    token.getPlatform(), token.getDeviceLabel());
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

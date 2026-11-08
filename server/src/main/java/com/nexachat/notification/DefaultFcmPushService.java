package com.nexachat.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                                          long seq, String senderName, String snippet) {
        List<UserDeviceToken> tokens = tokenRepository.findByUserId(recipientId);
        if (tokens.isEmpty()) {
            return;
        }

        Map<String, String> data = new HashMap<>();
        data.put("type", "NEW_MESSAGE");
        data.put("conversationId", conversationId.toString());
        data.put("messageId", messageId.toString());
        data.put("seq", String.valueOf(seq));
        data.put("senderName", senderName != null ? senderName : "");
        data.put("snippet", truncate(snippet, 100));

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
        for (UserDeviceToken token : tokens) {
            String tokenValue = token.getToken();
            fcmGateway.sendDataPush(tokenValue, data, priority, ttlSeconds)
                    .thenAccept(result -> {
                        if (result.unregistered()) {
                            log.warn("FCM token expired/unregistered, removing from DB: {}", tokenValue);
                            try {
                                if (deviceTokenService != null) {
                                    deviceTokenService.unregisterToken(tokenValue);
                                } else {
                                    tokenRepository.deleteByToken(tokenValue);
                                }
                            } catch (Exception ex) {
                                log.error("Failed to unregister expired device token {}: {}", tokenValue, ex.getMessage());
                            }
                        }
                    });
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String clean = text.strip();
        if (clean.length() <= maxLength) {
            return clean;
        }
        return clean.substring(0, maxLength);
    }
}

package com.nexachat.notification;

import java.util.List;
import java.util.UUID;

public interface FcmPushService {

    void sendNewMessageNotification(UUID recipientId, UUID conversationId, UUID messageId,
                                   long seq, String senderName);

    /**
     * Sends the new-message pointer to an explicit token list. The fan-out path
     * de-duplicates tokens across recipients first, so a token shared by
     * several accounts on one physical device receives the message once.
     */
    void sendNewMessageNotification(List<UserDeviceToken> tokens, UUID conversationId,
                                   UUID messageId, long seq, String senderName);

    default void sendNewMessageNotification(List<UserDeviceToken> tokens, UUID conversationId,
                                            UUID messageId, long seq, String senderName,
                                            boolean mentioned) {
        sendNewMessageNotification(tokens, conversationId, messageId, seq, senderName);
    }

    void sendCallInviteNotification(UUID recipientId, UUID callId, UUID conversationId,
                                    UUID callerId, String callerName, String callType, long timestamp);
}

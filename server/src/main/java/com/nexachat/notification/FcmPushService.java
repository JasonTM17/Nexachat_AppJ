package com.nexachat.notification;

import java.util.List;
import java.util.UUID;

public interface FcmPushService {

    default void sendNewMessageNotification(UUID recipientId, UUID conversationId, UUID messageId,
                                            long seq, String senderName) {
        sendNewMessageNotification(recipientId, conversationId, messageId, seq, senderName, false);
    }

    void sendNewMessageNotification(UUID recipientId, UUID conversationId, UUID messageId,
                                    long seq, String senderName, boolean mentioned);

    /**
     * Sends account-bound new-message pointers to an explicit token list.
     * Each recipient receives a separate payload, even when several accounts
     * share a physical device token.
     */
    default void sendNewMessageNotification(List<UserDeviceToken> tokens, UUID conversationId,
                                            UUID messageId, long seq, String senderName) {
        sendNewMessageNotification(tokens, conversationId, messageId, seq, senderName, false);
    }

    void sendNewMessageNotification(List<UserDeviceToken> tokens, UUID conversationId,
                                    UUID messageId, long seq, String senderName,
                                    boolean mentioned);

    void sendCallInviteNotification(UUID recipientId, UUID callId, UUID conversationId,
                                    UUID callerId, String callerName, String callType, long timestamp);
}

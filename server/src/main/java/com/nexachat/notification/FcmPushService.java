package com.nexachat.notification;

import java.util.UUID;

public interface FcmPushService {

    void sendNewMessageNotification(UUID recipientId, UUID conversationId, UUID messageId,
                                   long seq, String senderName);

    void sendCallInviteNotification(UUID recipientId, UUID callId, UUID conversationId,
                                   UUID callerId, String callerName, String callType, long timestamp);
}

package com.nexachat.feature.inbox;

/** Resolves whether a call preview was placed by the signed-in user. */
public final class CallPreviewDirection {
    private CallPreviewDirection() { }

    public static boolean isOutgoing(String content, String signedInUserId) {
        if (content == null || signedInUserId == null || signedInUserId.isEmpty()) {
            return false;
        }
        String[] parts = content.split("\\|", -1);
        return parts.length == 4
                && "CALL".equals(parts[0])
                && !parts[3].trim().isEmpty()
                && signedInUserId.equals(parts[3]);
    }
}

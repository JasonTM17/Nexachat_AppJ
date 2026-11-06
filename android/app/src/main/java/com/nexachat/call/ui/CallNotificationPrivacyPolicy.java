package com.nexachat.call.ui;

/** Keeps sensitive peer names in private notification content only. */
public final class CallNotificationPrivacyPolicy {
    private CallNotificationPrivacyPolicy() { }

    public static Content incoming(String peerName, String genericIncomingText) {
        return new Content(
                peerName,
                genericIncomingText,
                genericIncomingText,
                genericIncomingText);
    }

    public static Content ongoing(String peerName, String status, String genericOngoingText) {
        return new Content(peerName, status, genericOngoingText, genericOngoingText);
    }

    public static final class Content {
        private final String privateTitle;
        private final String privateText;
        private final String publicTitle;
        private final String publicText;

        private Content(String privateTitle, String privateText, String publicTitle, String publicText) {
            this.privateTitle = privateTitle;
            this.privateText = privateText;
            this.publicTitle = publicTitle;
            this.publicText = publicText;
        }

        public String getPrivateTitle() { return privateTitle; }
        public String getPrivateText() { return privateText; }
        public String getPublicTitle() { return publicTitle; }
        public String getPublicText() { return publicText; }
    }
}

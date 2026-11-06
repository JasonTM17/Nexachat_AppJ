package com.nexachat.call.ui;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class CallNotificationPrivacyPolicyTest {
    @Test
    public void incomingPublicVersionUsesGenericTextWithoutPeerName() {
        CallNotificationPrivacyPolicy.Content content =
                CallNotificationPrivacyPolicy.incoming("Alice", "Incoming voice call");

        assertEquals("Alice", content.getPrivateTitle());
        assertEquals("Incoming voice call", content.getPublicTitle());
        assertEquals("Incoming voice call", content.getPublicText());
        assertNotEquals("Alice", content.getPublicTitle());
    }

    @Test
    public void ongoingPublicVersionUsesGenericTextWithoutPeerName() {
        CallNotificationPrivacyPolicy.Content content =
                CallNotificationPrivacyPolicy.ongoing("Alice", "Voice call · 01:12", "Voice call in progress");

        assertEquals("Alice", content.getPrivateTitle());
        assertEquals("Voice call · 01:12", content.getPrivateText());
        assertEquals("Voice call in progress", content.getPublicTitle());
        assertEquals("Voice call in progress", content.getPublicText());
        assertNotEquals("Alice", content.getPublicText());
    }
}

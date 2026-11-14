package com.nexachat.feature.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class PrivacySwitchAccessibilityContractTest {
    @Test
    public void profileSwitchesAcceptNonTouchActivationAndObserveStoredState() throws Exception {
        String source = readSource("profile/ProfileFragment.java");
        assertFalse("An accessibility click need not put a switch in the pressed state",
                source.contains(".isPressed()"));
        for (String control : new String[] {"showPresence", "showSeen", "aiSummaryConsent"}) {
            assertTrue(control + " renders from the model instead of restoring widget state",
                    source.contains("binding." + control + ".setSaveEnabled(false)"));
        }
    }

    @Test
    public void conversationSwitchUsesTheStateObserverToSuppressProgrammaticWrites() throws Exception {
        String source = readSource("chat/ChatFragment.java");
        int start = source.indexOf("private void showConversationSettings()");
        int end = source.indexOf("private static int preferenceMessage", start);
        assertTrue(start >= 0 && end > start);
        String dialog = source.substring(start, end);
        int clearListener = dialog.indexOf("toggle.setOnCheckedChangeListener(null)");
        int renderState = dialog.indexOf("toggle.setChecked(state.getOptOut())");
        assertTrue(clearListener >= 0 && renderState > clearListener);
        assertFalse("The consent switch must accept accessibility clicks", dialog.contains(".isPressed()"));
    }

    private static String readSource(String relative) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/com/nexachat/feature", relative)),
                StandardCharsets.UTF_8);
    }
}

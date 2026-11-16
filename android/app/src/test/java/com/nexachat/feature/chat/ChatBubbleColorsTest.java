package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.ChatColor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Rules-level coverage for the chat-color application: the binder routes only
 * the MINE role through the selected palette (PEER and ERROR keep their fixed
 * resources), and the reply-quote tones derived inside an outgoing bubble keep
 * text-level contrast against their own container in both schemes.
 */
public final class ChatBubbleColorsTest {

    @Test
    public void bubbleFillIsTheSelectedVariantForEachScheme() {
        for (ChatColor color : ChatColor.values()) {
            assertEquals(color.name() + " day", color.getDayArgb(),
                    ChatBubbleColors.bubbleArgb(color, false));
            assertEquals(color.name() + " night", color.getNightArgb(),
                    ChatBubbleColors.bubbleArgb(color, true));
        }
    }

    @Test
    public void onlyTheMineRoleReadsTheSelectedColor() throws Exception {
        String source = new String(Files.readAllBytes(locate(
                "src/main/java/com/nexachat/feature/chat/adapter/MessageBubbleBinder.java",
                "android/app/src/main/java/com/nexachat/feature/chat/adapter/MessageBubbleBinder.java",
                "app/src/main/java/com/nexachat/feature/chat/adapter/MessageBubbleBinder.java")));
        int mineBranch = source.indexOf("case MINE:");
        int peerBranch = source.indexOf("case PEER:");
        int errorBranch = source.indexOf("case ERROR:");
        assertTrue("binder must route MINE through ChatBubbleColors", mineBranch > 0);
        assertTrue("PEER branch must exist after MINE", peerBranch > mineBranch);
        assertTrue("ERROR branch must exist", errorBranch > 0);
        String mineBody = source.substring(mineBranch, peerBranch);
        assertTrue("MINE must resolve the selected color",
                mineBody.contains("ChatBubbleColors.bubbleArgb"));
        assertTrue("PEER must keep the fixed incoming resource",
                source.substring(peerBranch).contains("R.color.bubble_other"));
        String errorBody = source.substring(
                errorBranch, Math.min(errorBranch + 300, source.length()));
        assertTrue("ERROR must keep the error container resource",
                errorBody.contains("R.color.colorErrorContainer"));
    }

    @Test
    public void quoteTextKeepsTextContrastOnItsContainerInBothSchemes() {
        for (ChatColor color : ChatColor.values()) {
            for (boolean night : new boolean[] {false, true}) {
                int bubble = ChatBubbleColors.bubbleArgb(color, night);
                int container = ChatBubbleColors.quoteContainerArgb(bubble, night);
                int content = ChatBubbleColors.quoteContentArgb(bubble, night);
                double ratio = contrastRatio(content, container);
                assertTrue(String.format(
                                "%s %s quote text/container: expected >= 4.5:1, was %.2f:1",
                                color.name(), night ? "night" : "day", ratio),
                        ratio >= 4.5);
            }
        }
    }

    @Test
    public void quoteAccentReadsAgainstItsContainerInBothSchemes() {
        for (ChatColor color : ChatColor.values()) {
            for (boolean night : new boolean[] {false, true}) {
                int bubble = ChatBubbleColors.bubbleArgb(color, night);
                int container = ChatBubbleColors.quoteContainerArgb(bubble, night);
                int accent = ChatBubbleColors.quoteAccentArgb(bubble, night);
                double ratio = contrastRatio(accent, container);
                assertTrue(String.format(
                                "%s %s quote accent/container: expected >= 3.0:1, was %.2f:1",
                                color.name(), night ? "night" : "day", ratio),
                        ratio >= 3.0);
            }
        }
    }

    @Test
    public void quoteTonesStayOpaque() {
        for (ChatColor color : ChatColor.values()) {
            for (boolean night : new boolean[] {false, true}) {
                int bubble = ChatBubbleColors.bubbleArgb(color, night);
                assertEquals(0xFF, (ChatBubbleColors.quoteContainerArgb(bubble, night) >>> 24) & 0xFF);
                assertEquals(0xFF, (ChatBubbleColors.quoteAccentArgb(bubble, night) >>> 24) & 0xFF);
                assertEquals(0xFF, (ChatBubbleColors.quoteContentArgb(bubble, night) >>> 24) & 0xFF);
            }
        }
    }

    private static double contrastRatio(int firstArgb, int secondArgb) {
        double bright = Math.max(luminance(firstArgb), luminance(secondArgb));
        double dark = Math.min(luminance(firstArgb), luminance(secondArgb));
        return (bright + 0.05) / (dark + 0.05);
    }

    private static double luminance(int argb) {
        return 0.2126d * channel((argb >> 16) & 0xFF)
                + 0.7152d * channel((argb >> 8) & 0xFF)
                + 0.0722d * channel(argb & 0xFF);
    }

    private static double channel(int value) {
        double srgb = value / 255.0d;
        return srgb <= 0.04045d
                ? srgb / 12.92d
                : Math.pow((srgb + 0.055d) / 1.055d, 2.4d);
    }

    private static Path locate(String... candidates) {
        for (String relative : candidates) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("Could not locate MessageBubbleBinder source");
    }
}

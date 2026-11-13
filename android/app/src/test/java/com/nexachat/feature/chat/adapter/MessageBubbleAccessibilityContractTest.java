package com.nexachat.feature.chat.adapter;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public final class MessageBubbleAccessibilityContractTest {

    @Test
    public void sendingProgressHasItsLocalizedDescription() throws Exception {
        String source = readSource("feature/chat/adapter/MessageBubbleBinder.java");
        int start = source.indexOf("boolean sendingNow =");
        int end = source.indexOf("if (deliveredIcon != null)", start);
        assertTrue("sending metadata block must exist", start >= 0 && end > start);
        String block = source.substring(start, end);
        assertTrue("sending progress must receive a content description",
                block.contains("sending.setContentDescription("));
        assertTrue("sending progress must use cd_sending",
                block.contains("R.string.cd_sending"));
    }

    @Test
    public void readReceiptUsesVisibilityGlyph() throws Exception {
        String source = readSource("feature/chat/adapter/MessageBubbleBinder.java");
        String readBranch = between(source,
                "else if (message.getStatus() == DeliveryStatus.READ || seen)",
                "} else if (message.getStatus() == DeliveryStatus.DELIVERED || delivered)");
        assertTrue("read receipt must use the distinct visibility glyph",
                readBranch.contains("R.drawable.ic_visibility"));
    }

    @Test
    public void reactionByMeUsesLocalizedDescription() throws Exception {
        String source = readSource("feature/chat/adapter/MessageBubbleBinder.java");
        String reactionLoop = between(source,
                "for (ReactionChip chip : reactions)",
                "view.setCheckable(false)").replaceAll("\\s+", "");
        assertTrue("reaction label must branch on reactedByMe",
                reactionLoop.contains("mineReaction?R.string.a11y_reaction_chip_by_me"));
        assertTrue("reaction label must retain the ordinary localized description",
                reactionLoop.contains(":R.string.a11y_reaction_chip"));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue("source section must exist: " + startMarker, start >= 0 && end > start);
        return source.substring(start, end);
    }

    private static String readSource(String relativePath) throws Exception {
        File[] candidates = new File[]{
                new File("src/main/java/com/nexachat/" + relativePath),
                new File("app/src/main/java/com/nexachat/" + relativePath),
                new File("android/app/src/main/java/com/nexachat/" + relativePath),
                new File("../app/src/main/java/com/nexachat/" + relativePath)
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Could not locate source " + relativePath);
    }
}

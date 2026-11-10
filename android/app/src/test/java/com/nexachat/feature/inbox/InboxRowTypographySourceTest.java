package com.nexachat.feature.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class InboxRowTypographySourceTest {

    @Test
    public void resolvesInterBeforeApplyingUnreadStyle() throws Exception {
        String source = readTypoSource();
        assertTrue(source.contains("ResourcesCompat.getFont"));
        assertTrue(source.contains("R.font.inter"));
        assertTrue(source.contains("Typeface.create(resolved, style)"));
        assertFalse(source.contains("setTypeface(null"));
    }

    @Test
    public void conversationListAdapter_defersToInboxRowTypography() throws Exception {
        String source = readAdapterSource();
        assertTrue(source.contains("InboxRowTypography.applyUnreadWeight"));
        assertFalse(source.contains("setTypeface(null"));
    }

    private static String readTypoSource() throws Exception {
        return new String(Files.readAllBytes(locate(
                "src/main/java/com/nexachat/feature/inbox/InboxRowTypography.java",
                "android/app/src/main/java/com/nexachat/feature/inbox/InboxRowTypography.java",
                "app/src/main/java/com/nexachat/feature/inbox/InboxRowTypography.java")));
    }

    private static String readAdapterSource() throws Exception {
        return new String(Files.readAllBytes(locate(
                "src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java",
                "android/app/src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java",
                "app/src/main/java/com/nexachat/feature/inbox/adapter/ConversationListAdapter.java")));
    }

    private static Path locate(String... candidates) {
        for (String relative : candidates) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate inbox typography sources");
    }
}

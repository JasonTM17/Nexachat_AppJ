package com.nexachat.feature.contacts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class ListRowLargeTypeSourceTest {

    @Test
    public void contactListAdapter_appliesLargeTypeThresholdInBinder() throws Exception {
        String source = readSource("ContactListAdapter.java",
                "src/main/java/com/nexachat/feature/contacts/adapter/ContactListAdapter.java",
                "android/app/src/main/java/com/nexachat/feature/contacts/adapter/ContactListAdapter.java",
                "app/src/main/java/com/nexachat/feature/contacts/adapter/ContactListAdapter.java");
        assertTrue(source.contains("fontScale >= 1.3f"));
        assertTrue(source.contains("setMaxLines"));
    }

    @Test
    public void userSearchAdapter_appliesLargeTypeThresholdInBinder() throws Exception {
        String source = readSource("UserSearchAdapter.java",
                "src/main/java/com/nexachat/feature/contacts/adapter/UserSearchAdapter.java",
                "android/app/src/main/java/com/nexachat/feature/contacts/adapter/UserSearchAdapter.java",
                "app/src/main/java/com/nexachat/feature/contacts/adapter/UserSearchAdapter.java");
        assertTrue(source.contains("fontScale >= 1.3f"));
        assertTrue(source.contains("setMaxLines"));
    }

    @Test
    public void groupMemberAdapter_usesLargeTypeThresholdAtOnePointThree() throws Exception {
        String source = readSource("GroupMemberAdapter.java",
                "src/main/java/com/nexachat/feature/group/adapter/GroupMemberAdapter.java",
                "android/app/src/main/java/com/nexachat/feature/group/adapter/GroupMemberAdapter.java",
                "app/src/main/java/com/nexachat/feature/group/adapter/GroupMemberAdapter.java");
        assertTrue(source.contains("fontScale >= 1.3f"));
        assertFalse(source.contains("fontScale >= 1.5f"));
    }

    private static String readSource(String label, String... candidates) throws Exception {
        return new String(Files.readAllBytes(locate(label, candidates)));
    }

    private static Path locate(String label, String... candidates) {
        for (String relative : candidates) {
            Path path = Path.of(relative);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new AssertionError("Could not locate " + label);
    }
}

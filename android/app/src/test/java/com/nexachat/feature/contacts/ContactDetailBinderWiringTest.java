package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;

/**
 * Guards the extraction: contacts and user search must use the one shared
 * detail binder instead of carrying their own copies of the binding code.
 */
public final class ContactDetailBinderWiringTest {

    private Path sourceDir;

    @Before
    public void setUp() {
        sourceDir = locateSourceDir();
    }

    @Test
    public void bothScreensRenderDetailThroughTheSharedBinder() throws IOException {
        String contacts = read("ContactsFragment.java");
        String search = read("UserSearchFragment.java");

        assertTrue(contacts.contains("new ContactDetailBinder(this, viewModel)"));
        assertTrue(contacts.contains("detailBinder.render(state)"));
        assertTrue(search.contains("new ContactDetailBinder(this, viewModel)"));
        assertTrue(search.contains("detailBinder.render(state)"));
    }

    @Test
    public void detailBindingExistsOnlyInTheSharedBinder() throws IOException {
        String contacts = read("ContactsFragment.java");
        String search = read("UserSearchFragment.java");
        String binder = read("ContactDetailBinder.java");

        for (String fragment : new String[] {contacts, search}) {
            assertFalse("fragment still builds the detail avatar", fragment.contains("new AvatarView("));
            assertFalse("fragment still builds the detail dialog",
                    fragment.contains("showOrUpdateDetailDialog("));
            assertFalse("fragment still binds detail content",
                    fragment.contains("bindDetailView(") || fragment.contains("bindDetailContent("));
            assertFalse("fragment still owns the alias field", fragment.contains("detailAliasInput"));
            assertFalse("fragment still keeps detail state fields",
                    fragment.contains("detailBoundUserId"));
            assertFalse("fragment still labels the detail action",
                    fragment.contains("R.string.action_message"));
        }

        assertEquals(1, occurrences(binder, "R.string.action_message"));
        assertEquals(1, occurrences(binder, "R.string.action_save"));
        assertEquals(1, occurrences(binder, "R.string.confirm_remove_contact"));
        assertEquals(1, occurrences(binder, "ContactErrorIds.messageId("));
        assertFalse(binder.contains("ContactsFragment"));
        assertFalse(binder.contains("UserSearchFragment"));
    }

    private String read(String file) throws IOException {
        return new String(Files.readAllBytes(sourceDir.resolve(file)), StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static Path locateSourceDir() {
        Path[] candidates = new Path[] {
                Path.of("src/main/java/com/nexachat/feature/contacts"),
                Path.of("app/src/main/java/com/nexachat/feature/contacts"),
                Path.of("android/app/src/main/java/com/nexachat/feature/contacts")
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)
                    && Files.isRegularFile(candidate.resolve("ContactsFragment.java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate feature/contacts sources");
    }
}

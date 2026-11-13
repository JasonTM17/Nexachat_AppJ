package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.Contact;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class GroupContactPickerSupportTest {

    @Test
    public void selectionRebindIndices_emptyWhenSelectionUnchanged() {
        List<Contact> contacts = List.of(contact("a"), contact("b"));
        Set<String> selected = Set.of("a");

        assertTrue(GroupContactPickerSupport.selectionRebindIndices(contacts, selected, selected).isEmpty());
    }

    @Test
    public void selectionRebindIndices_listsToggledRowsOnly() {
        List<Contact> contacts = List.of(contact("a"), contact("b"), contact("c"));
        Set<String> before = Set.of("a", "c");
        Set<String> after = Set.of("b", "c");

        assertEquals(List.of(0, 1), GroupContactPickerSupport.selectionRebindIndices(contacts, before, after));
    }

    private static Contact contact(String id) {
        return new Contact(id, null, "User " + id, null, null, null);
    }
}

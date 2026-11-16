package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.Contact;
import java.util.List;
import org.junit.Test;

/**
 * Section grouping orders contacts by the label each row shows — the owner
 * alias when set, else the profile name — so every letter heads one
 * contiguous bucket even when the incoming server order (display name)
 * disagrees, and marks the first/last row of each bucket so the adapter can
 * pick the right rounded band background per row.
 */
public final class ContactSectionsTest {

    @Test
    public void letterFor_usesFirstAlphaAfterDiacriticStrip() {
        assertEquals("L", ContactSections.letterFor("Lan Anh"));
        assertEquals("D", ContactSections.letterFor("Đức Anh"));
        assertEquals("B", ContactSections.letterFor("  bình  "));
    }

    @Test
    public void letterFor_groupsNonLettersUnderHash() {
        assertEquals("#", ContactSections.letterFor(""));
        assertEquals("#", ContactSections.letterFor("   "));
        assertEquals("#", ContactSections.letterFor("0900000210"));
        assertEquals("#", ContactSections.letterFor("日本語"));
        assertEquals("#", ContactSections.letterFor("🙂 Minh"));
    }

    @Test
    public void group_emitsOneHeaderPerContiguousLetterBucket() {
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "An Tuan"),
                contact("2", "An Vu"),
                contact("3", "Bao Chau"),
                contact("4", "Duc Anh")));

        assertEquals(7, rows.size());
        assertHeader(rows.get(0), "A");
        assertItem(rows.get(1), "1", true, false);
        assertItem(rows.get(2), "2", false, true);
        assertHeader(rows.get(3), "B");
        assertItem(rows.get(4), "3", true, true);
        assertHeader(rows.get(5), "D");
        assertItem(rows.get(6), "4", true, true);
    }

    @Test
    public void group_singleContactIsFirstAndLastOfItsSection() {
        List<ContactSections.Row> rows =
                ContactSections.group(List.of(contact("1", "Minh Khoa")));

        assertEquals(2, rows.size());
        assertHeader(rows.get(0), "M");
        assertItem(rows.get(1), "1", true, true);
    }

    @Test
    public void group_mergesSameLetterIntoOneSection() {
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "An Tuan"),
                contact("2", "Bao Chau"),
                contact("3", "An Vu")));

        assertEquals(5, rows.size());
        assertHeader(rows.get(0), "A");
        assertItem(rows.get(1), "1", true, false);
        assertItem(rows.get(2), "3", false, true);
        assertHeader(rows.get(3), "B");
        assertItem(rows.get(4), "2", true, true);
    }

    @Test
    public void group_aliasedContactSectionsByTheLabelTheRowShows() {
        // The server orders by display name; "Zed" is an alias over the
        // display name "Anh Vu", so without re-sorting its row lands under
        // an "A" bucket while carrying a "Z" letter.
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "An Tuan", null),
                contact("2", "Anh Vu", "Zed"),
                contact("3", "Bao Chau", null)));

        assertEquals(6, rows.size());
        assertHeader(rows.get(0), "A");
        assertItem(rows.get(1), "1", true, true);
        assertHeader(rows.get(2), "B");
        assertItem(rows.get(3), "3", true, true);
        assertHeader(rows.get(4), "Z");
        assertItem(rows.get(5), "2", true, true);
    }

    @Test
    public void group_hashSectionSortsAfterLetters() {
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "0900000210"),
                contact("2", "Bao Chau")));

        assertEquals(4, rows.size());
        assertHeader(rows.get(0), "B");
        assertItem(rows.get(1), "2", true, true);
        assertHeader(rows.get(2), "#");
        assertItem(rows.get(3), "1", true, true);
    }

    @Test
    public void group_emptyListYieldsNoRows() {
        assertTrue(ContactSections.group(List.of()).isEmpty());
    }

    private static Contact contact(String userId, String displayName) {
        return contact(userId, displayName, null);
    }

    private static Contact contact(String userId, String displayName, String aliasName) {
        return new Contact(userId, null, displayName, aliasName, null, null);
    }

    private static void assertHeader(ContactSections.Row row, String letter) {
        assertTrue(row instanceof ContactSections.Row.Header);
        assertEquals(letter, ((ContactSections.Row.Header) row).getLetter());
    }

    private static void assertItem(
            ContactSections.Row row, String userId, boolean first, boolean last) {
        assertTrue(row instanceof ContactSections.Row.Item);
        ContactSections.Row.Item item = (ContactSections.Row.Item) row;
        assertEquals(userId, item.getContact().getUserId());
        assertEquals(first, item.isFirstInSection());
        assertEquals(last, item.isLastInSection());
    }
}

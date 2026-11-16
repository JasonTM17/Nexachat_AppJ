package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.Contact;
import java.util.List;
import org.junit.Test;

/**
 * Section grouping keeps the incoming display-name order and only marks the
 * first/last row of each contiguous same-letter bucket, so the adapter can
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
                contact("2", "Anh Vu"),
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
    public void group_startsNewSectionWhenLetterChangesBack() {
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "An Tuan"),
                contact("2", "Bao Chau"),
                contact("3", "Anh Em")));

        assertEquals(6, rows.size());
        assertHeader(rows.get(0), "A");
        assertHeader(rows.get(2), "B");
        assertHeader(rows.get(4), "A");
    }

    @Test
    public void group_emptyListYieldsNoRows() {
        assertTrue(ContactSections.group(List.of()).isEmpty());
    }

    private static Contact contact(String userId, String displayName) {
        return new Contact(userId, null, displayName, null, null, null);
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

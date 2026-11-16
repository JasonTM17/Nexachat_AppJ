package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.Contact;
import java.util.List;
import org.junit.Test;

/**
 * The letter rail derives everything from the grouped rows: it shows only
 * letters that head a section, maps a letter to its header position for the
 * jump, and clamps a drag fraction onto the nearest cell.
 */
public final class ContactLetterIndexTest {

    @Test
    public void letters_returnsOnlyLettersThatHeadASection() {
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "An Tuan"),
                contact("2", "Bao Chau"),
                contact("3", "An Vu"),
                contact("4", "Minh Khoa")));

        assertEquals(List.of("A", "B", "M"), ContactLetterIndex.letters(rows));
    }

    @Test
    public void letters_emptyListYieldsNoLetters() {
        assertTrue(ContactLetterIndex.letters(ContactSections.group(List.of())).isEmpty());
    }

    @Test
    public void adapterPosition_pointsAtTheHeaderRow() {
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "An Tuan"),
                contact("2", "Bao Chau"),
                contact("3", "Bao Nam")));

        assertEquals(0, ContactLetterIndex.adapterPosition(rows, "A"));
        assertEquals(2, ContactLetterIndex.adapterPosition(rows, "B"));
    }

    @Test
    public void adapterPosition_sectionOfOneStillResolves() {
        List<ContactSections.Row> rows =
                ContactSections.group(List.of(contact("1", "Minh Khoa")));

        assertEquals(0, ContactLetterIndex.adapterPosition(rows, "M"));
    }

    @Test
    public void adapterPosition_missingLetterAndEmptyListReturnMinusOne() {
        List<ContactSections.Row> rows =
                ContactSections.group(List.of(contact("1", "An Tuan")));

        assertEquals(-1, ContactLetterIndex.adapterPosition(rows, "Z"));
        assertEquals(-1, ContactLetterIndex.adapterPosition(rows, null));
        assertEquals(-1, ContactLetterIndex.adapterPosition(List.of(), "A"));
    }

    @Test
    public void letters_vietnameseDiacriticsFoldOntoLatinSections() {
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "Đức Anh"),
                contact("2", "Ấn Danh"),
                contact("3", "Ý Nhi")));

        assertEquals(List.of("A", "D", "Y"), ContactLetterIndex.letters(rows));
        assertEquals(2, ContactLetterIndex.adapterPosition(rows, "D"));
    }

    @Test
    public void letters_hashBucketSortsLastAndResolves() {
        List<ContactSections.Row> rows = ContactSections.group(List.of(
                contact("1", "0900000210"),
                contact("2", "Bao Chau"),
                contact("3", "🙂 Minh")));

        assertEquals(List.of("B", "#"), ContactLetterIndex.letters(rows));
        assertEquals(2, ContactLetterIndex.adapterPosition(rows, "#"));
    }

    @Test
    public void indexAtFraction_mapsAndClampsOntoCells() {
        assertEquals(0, ContactLetterIndex.indexAtFraction(4, 0f));
        assertEquals(0, ContactLetterIndex.indexAtFraction(4, 0.24f));
        assertEquals(1, ContactLetterIndex.indexAtFraction(4, 0.25f));
        assertEquals(2, ContactLetterIndex.indexAtFraction(4, 0.5f));
        assertEquals(3, ContactLetterIndex.indexAtFraction(4, 0.99f));
        assertEquals(3, ContactLetterIndex.indexAtFraction(4, 1f));
        assertEquals(3, ContactLetterIndex.indexAtFraction(4, 2.5f));
        assertEquals(0, ContactLetterIndex.indexAtFraction(4, -0.4f));
    }

    @Test
    public void indexAtFraction_emptyRailHasNoCell() {
        assertEquals(-1, ContactLetterIndex.indexAtFraction(0, 0.5f));
    }

    @Test
    public void fitToCells_returnsEveryLetterWhenTheyFit() {
        List<String> letters = List.of("A", "B", "C", "D");
        assertEquals(letters, ContactLetterIndex.fitToCells(letters, 4));
        assertEquals(letters, ContactLetterIndex.fitToCells(letters, 12));
    }

    @Test
    public void fitToCells_samplesEveryOtherLetterWhenTheyOverflow() {
        List<String> letters =
                List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "#");
        // 12 sections in 7 cells: stride 2, first and last ('#') stay.
        assertEquals(List.of("A", "C", "E", "G", "I", "K", "#"),
                ContactLetterIndex.fitToCells(letters, 7));
    }

    @Test
    public void fitToCells_widensTheStrideForTightRails() {
        List<String> letters =
                List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "#");
        // Stride 3 keeps 5 cells; a two-cell rail keeps the first and '#'.
        assertEquals(List.of("A", "D", "G", "J", "#"),
                ContactLetterIndex.fitToCells(letters, 5));
        assertEquals(List.of("A", "#"), ContactLetterIndex.fitToCells(letters, 2));
        assertEquals(List.of("A"), ContactLetterIndex.fitToCells(letters, 1));
    }

    @Test
    public void fitToCells_neverOverflowsAndDropsNothingUnderTheCap() {
        List<String> letters = ContactLetterIndex.letters(ContactSections.group(List.of(
                contact("1", "An Tuan"), contact("2", "Bao Chau"), contact("3", "Cuong Le"),
                contact("4", "Duc Anh"), contact("5", "Em Ly"), contact("6", "Gia Huy"),
                contact("7", "Ha My"), contact("8", "Khanh Vy"), contact("9", "Lam Ngoc"),
                contact("10", "Minh Khoa"), contact("11", "Ngoc Anh"), contact("12", "Phuc La"),
                contact("13", "Quynh Nhu"), contact("14", "Son Tung"), contact("15", "Tram Anh"),
                contact("16", "Uyen Thy"), contact("17", "Viet Hoang"), contact("18", "0900000210"))));
        for (int maxCells = 2; maxCells < letters.size(); maxCells++) {
            List<String> fitted = ContactLetterIndex.fitToCells(letters, maxCells);
            assertTrue(fitted.size() <= maxCells);
            assertEquals(letters.get(0), fitted.get(0));
            assertEquals(letters.get(letters.size() - 1), fitted.get(fitted.size() - 1));
            assertEquals("#", fitted.get(fitted.size() - 1));
        }
        assertTrue(ContactLetterIndex.fitToCells(List.of(), 5).isEmpty());
        assertTrue(ContactLetterIndex.fitToCells(letters, 0).isEmpty());
    }

    @Test
    public void isRailVisible_needsEnoughSectionsAndFittingType() {
        assertFalse(ContactLetterIndex.isRailVisible(0, 1.0f));
        assertFalse(ContactLetterIndex.isRailVisible(2, 1.0f));
        assertTrue(ContactLetterIndex.isRailVisible(3, 1.0f));
        assertTrue(ContactLetterIndex.isRailVisible(26, 1.29f));
        assertFalse(ContactLetterIndex.isRailVisible(26, 1.3f));
        assertFalse(ContactLetterIndex.isRailVisible(26, 2.0f));
    }

    private static Contact contact(String userId, String displayName) {
        return new Contact(userId, null, displayName, null, null, null);
    }
}

package com.nexachat.feature.contacts;

import androidx.annotation.NonNull;
import com.nexachat.data.Contact;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Expands the contact list into rows: one letter badge header per letter
 * bucket plus the contact rows in it. Contacts are ordered by the same label
 * the row shows ({@link Contact#getPrimaryLabel()}), so each letter heads one
 * contiguous section even when the incoming order differs — the server sorts
 * by display name while a contact's alias can lead the row.
 */
public final class ContactSections {

    /**
     * Orders by section letter first (A–Z, then "#") and inside a bucket by
     * the displayed label under the device collator. The sort is stable, so
     * equal labels keep the incoming order.
     */
    private static final Comparator<Contact> SECTION_ORDER = Comparator
            .comparingInt((Contact contact) -> sectionRank(letterFor(contact.getPrimaryLabel())))
            .thenComparing(Contact::getPrimaryLabel, Collator.getInstance());

    private ContactSections() {
    }

    public sealed interface Row permits Row.Header, Row.Item {

        final class Header implements Row {
            private final String letter;

            Header(@NonNull String letter) {
                this.letter = letter;
            }

            @NonNull
            public String getLetter() {
                return letter;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof Header header && letter.equals(header.letter);
            }

            @Override
            public int hashCode() {
                return letter.hashCode();
            }
        }

        final class Item implements Row {
            private final Contact contact;
            private final boolean firstInSection;
            private final boolean lastInSection;

            Item(@NonNull Contact contact,
                    boolean firstInSection,
                    boolean lastInSection) {
                this.contact = contact;
                this.firstInSection = firstInSection;
                this.lastInSection = lastInSection;
            }

            @NonNull
            public Contact getContact() {
                return contact;
            }

            public boolean isFirstInSection() {
                return firstInSection;
            }

            public boolean isLastInSection() {
                return lastInSection;
            }

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof Item item)) {
                    return false;
                }
                return firstInSection == item.firstInSection
                        && lastInSection == item.lastInSection
                        && contact.equals(item.contact);
            }

            @Override
            public int hashCode() {
                return Objects.hash(contact, firstInSection, lastInSection);
            }
        }
    }

    public static List<Row> group(@NonNull List<Contact> contacts) {
        List<Contact> ordered = new ArrayList<>(contacts);
        ordered.sort(SECTION_ORDER);

        List<Row> rows = new ArrayList<>(ordered.size() + 4);
        String[] letters = new String[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            letters[i] = letterFor(ordered.get(i).getPrimaryLabel());
        }

        String lastLetter = null;
        for (int i = 0; i < ordered.size(); i++) {
            if (!letters[i].equals(lastLetter)) {
                lastLetter = letters[i];
                rows.add(new Row.Header(lastLetter));
            }
            boolean first = i == 0 || !letters[i - 1].equals(letters[i]);
            boolean last = i == ordered.size() - 1 || !letters[i + 1].equals(letters[i]);
            rows.add(new Row.Item(ordered.get(i), first, last));
        }
        return rows;
    }

    /** A–Z keep alphabetical order; the "#" bucket sorts after every letter. */
    private static int sectionRank(@NonNull String letter) {
        return letter.charAt(0) == '#' ? 26 : letter.charAt(0) - 'A';
    }

    /**
     * Section letter for a display label: the first character after diacritic
     * stripping when it is a–z, upper-cased; anything else (digits, emoji,
     * non-Latin scripts, blanks) groups under {@code "#"}.
     */
    @NonNull
    static String letterFor(@NonNull String label) {
        String normalized = ContactQueryFilter.normalizeText(label);
        if (normalized.isEmpty()) {
            return "#";
        }
        char c = normalized.charAt(0);
        return c >= 'a' && c <= 'z' ? String.valueOf(Character.toUpperCase(c)) : "#";
    }
}

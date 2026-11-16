package com.nexachat.feature.contacts;

import androidx.annotation.NonNull;
import com.nexachat.data.Contact;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Expands the already display-name-sorted contact list into rows: one letter
 * badge header per contiguous alphabetical bucket plus the contact rows in it.
 * Sorting stays wherever the data comes from; this only groups what arrives.
 */
public final class ContactSections {

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
        List<Row> rows = new ArrayList<>(contacts.size() + 4);
        String[] letters = new String[contacts.size()];
        for (int i = 0; i < contacts.size(); i++) {
            letters[i] = letterFor(contacts.get(i).getPrimaryLabel());
        }

        String lastLetter = null;
        for (int i = 0; i < contacts.size(); i++) {
            if (!letters[i].equals(lastLetter)) {
                lastLetter = letters[i];
                rows.add(new Row.Header(lastLetter));
            }
            boolean first = i == 0 || !letters[i - 1].equals(letters[i]);
            boolean last = i == contacts.size() - 1 || !letters[i + 1].equals(letters[i]);
            rows.add(new Row.Item(contacts.get(i), first, last));
        }
        return rows;
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

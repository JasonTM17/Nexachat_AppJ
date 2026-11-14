package com.nexachat.feature.contacts;

import androidx.annotation.Nullable;
import com.nexachat.data.Contact;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local as-you-type filter over the saved contact list. Matching is
 * case-insensitive and Vietnamese-diacritic-insensitive on the contact label,
 * and digits-only on the phone number. Server search stays on explicit
 * submit because the endpoint is rate-limited.
 */
public final class ContactQueryFilter {

    private ContactQueryFilter() {
    }

    /**
     * Returns the contacts matching {@code query}; a blank query returns the
     * list unchanged.
     */
    public static List<Contact> filter(@Nullable List<Contact> contacts, @Nullable String query) {
        if (contacts == null || contacts.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return contacts;
        }
        String needleText = normalizeText(query);
        String needleDigits = digitsOnly(query);
        List<Contact> matched = new ArrayList<>();
        for (Contact contact : contacts) {
            String label = normalizeText(contact.getPrimaryLabel());
            if (label.contains(needleText)) {
                matched.add(contact);
                continue;
            }
            if (!needleDigits.isEmpty()
                    && digitsOnly(contact.getPhone()).contains(needleDigits)) {
                matched.add(contact);
            }
        }
        return matched;
    }

    /**
     * Lower-cases and strips diacritics so "binh" matches "Bình". {@code đ/Đ}
     * has no NFD decomposition and is mapped to {@code d} explicitly.
     */
    static String normalizeText(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT).trim();
    }

    private static String digitsOnly(@Nullable String value) {
        if (value == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isDigit(character)) {
                digits.append(character);
            }
        }
        return digits.toString();
    }
}

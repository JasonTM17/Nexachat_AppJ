package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.Contact;
import java.util.List;
import org.junit.Test;

public final class ContactQueryFilterTest {

    private static Contact contact(String id, String phone, String name) {
        return new Contact(id, phone, name, null, null, null);
    }

    private static Contact aliased(String id, String phone, String name, String alias) {
        return new Contact(id, phone, name, alias, null, null);
    }

    @Test
    public void blankQueryReturnsTheListUnchanged() {
        List<Contact> contacts = List.of(
                contact("1", "+84900000002", "Binh"),
                contact("2", "+84900000003", "Chi"));

        assertSame(contacts, ContactQueryFilter.filter(contacts, " "));
        assertSame(contacts, ContactQueryFilter.filter(contacts, null));
    }

    @Test
    public void nameMatchIsCaseAndDiacriticInsensitive() {
        Contact binh = contact("1", "+84900000002", "Bình Nguyễn");
        Contact chi = contact("2", "+84900000003", "Chi Trần");

        assertEquals(List.of(binh), ContactQueryFilter.filter(List.of(binh, chi), "binh"));
        assertEquals(List.of(binh), ContactQueryFilter.filter(List.of(binh, chi), "BINH"));
        assertEquals(List.of(binh), ContactQueryFilter.filter(List.of(binh, chi), "nguyen"));
    }

    @Test
    public void strokeDIsNormalizedForVietnameseNames() {
        Contact duong = contact("1", "+84900000004", "Đường");

        assertEquals(List.of(duong), ContactQueryFilter.filter(List.of(duong), "duong"));
        assertEquals(List.of(duong), ContactQueryFilter.filter(List.of(duong), "Đường"));
    }

    @Test
    public void aliasLabelIsSearchable() {
        Contact an = aliased("1", "+84900000001", "An", "Sếp");

        assertEquals(List.of(an), ContactQueryFilter.filter(List.of(an), "sep"));
    }

    @Test
    public void phoneMatchUsesDigitsOnly() {
        Contact binh = contact("1", "+84 900 000 02", "Binh");
        Contact chi = contact("2", "+84933000033", "Chi");

        assertEquals(List.of(binh), ContactQueryFilter.filter(List.of(binh, chi), "9000"));
        assertEquals(List.of(binh), ContactQueryFilter.filter(List.of(binh, chi), "+84 900"));
        assertEquals(List.of(chi), ContactQueryFilter.filter(List.of(binh, chi), "033"));
    }

    @Test
    public void noMatchReturnsEmptyList() {
        Contact binh = contact("1", "+84900000002", "Binh");

        assertTrue(ContactQueryFilter.filter(List.of(binh), "zzz").isEmpty());
        assertTrue(ContactQueryFilter.filter(null, "binh").isEmpty());
    }
}

package com.nexachat.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ContactTest {

    @Test
    public void nonBlankAliasOverridesProfileNameIncludingVietnameseText() {
        Contact contact = new Contact("u1", "+84912345678", "Nguyễn An", "Bạn thân", null, null);

        assertEquals("Bạn thân", contact.getPrimaryLabel());
    }

    @Test
    public void nullOrWhitespaceAliasFallsBackToDisplayName() {
        Contact noAlias = new Contact("u1", "+84912345678", "Nguyễn An", null, null, null);
        Contact blankAlias = new Contact("u2", "+84912345679", "Bình", " \t ", null, null);

        assertEquals("Nguyễn An", noAlias.getPrimaryLabel());
        assertEquals("Bình", blankAlias.getPrimaryLabel());
    }
}

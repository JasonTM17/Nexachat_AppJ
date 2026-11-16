package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Contract between the Android language catalog and the server's supported set. */
public final class TranslationLanguagesTest {

    /** Server {@code TranslationLanguage} codes — keep in lockstep. */
    private static final List<String> SERVER_CODES = List.of(
            "vi", "en", "zh", "ja", "ko", "fr", "de", "es", "th", "id", "ru", "ar", "pt", "hi");

    @Test public void catalogMatchesServerSupportedSet() {
        assertEquals(SERVER_CODES.size(), TranslationLanguages.SUPPORTED.size());
        for (int i = 0; i < SERVER_CODES.size(); i++) {
            assertEquals(SERVER_CODES.get(i), TranslationLanguages.SUPPORTED.get(i).code());
        }
    }

    @Test public void codesAreUniqueAndResolvable() {
        Set<String> seen = new HashSet<>();
        for (TranslationLanguages.Entry entry : TranslationLanguages.SUPPORTED) {
            assertTrue("duplicate code " + entry.code(), seen.add(entry.code()));
            assertNotEquals(0, entry.nameRes());
            assertEquals(entry.nameRes(), TranslationLanguages.nameRes(entry.code()));
        }
    }

    @Test public void unknownCodeResolvesToZero() {
        assertEquals(0, TranslationLanguages.nameRes("xx"));
    }
}

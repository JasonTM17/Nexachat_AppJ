package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Factory and equality contract for per-message translation state. */
public final class TranslationUiStateTest {

    @Test public void factoriesPopulateExpectedFields() {
        TranslationUiState loading = TranslationUiState.loading("en");
        assertEquals(TranslationUiState.Status.LOADING, loading.getStatus());
        assertEquals("en", loading.getTargetLang());
        assertNull(loading.getText());

        TranslationUiState ready = TranslationUiState.ready("xin chào", "vi", "en");
        assertEquals(TranslationUiState.Status.READY, ready.getStatus());
        assertEquals("xin chào", ready.getText());
        assertEquals("vi", ready.getSourceLang());
        assertNull(ready.getErrorCode());

        TranslationUiState error = TranslationUiState.error("en", "RATE_LIMITED");
        assertEquals(TranslationUiState.Status.ERROR, error.getStatus());
        assertEquals("RATE_LIMITED", error.getErrorCode());
        assertNull(error.getText());
    }

    @Test public void equalityCoversAllFields() {
        assertEquals(
                TranslationUiState.ready("a", "vi", "en"),
                TranslationUiState.ready("a", "vi", "en"));
        assertNotEquals(
                TranslationUiState.ready("a", "vi", "en"),
                TranslationUiState.ready("b", "vi", "en"));
        assertNotEquals(
                TranslationUiState.ready("a", "vi", "en"),
                TranslationUiState.error("en", null));
        assertNotEquals(
                TranslationUiState.ready("a", "vi", "en"),
                TranslationUiState.ready("a", "vi", "fr"));
    }
}

package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseFilterTest {
    @Test
    void filtersEmptyAcknowledgementsLaughterStickersSymbolsAndLinks() {
        assertTrue(NoiseFilter.isNoise(null));
        assertTrue(NoiseFilter.isNoise("  \n "));
        assertTrue(NoiseFilter.isNoise("ok"));
        assertTrue(NoiseFilter.isNoise("=))"));
        assertTrue(NoiseFilter.isNoise("hahaha!!!"));
        assertTrue(NoiseFilter.isNoise("😂"));
        assertTrue(NoiseFilter.isNoise("sticker"));
        assertTrue(NoiseFilter.isNoise("https://example.invalid/path?ref=chat"));
    }

    @Test
    void keepsMessagesThatContainContentAndDoesNotMatchAcknowledgementSubstrings() {
        assertFalse(NoiseFilter.isNoise("Please send the screen https://example.invalid/view"));
        assertFalse(NoiseFilter.isNoise("OKRs are listed in the project plan."));
        assertFalse(NoiseFilter.isNoise("This is not laughter: haha."));
    }
}

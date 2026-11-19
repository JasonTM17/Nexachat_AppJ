package com.nexachat.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the observed {@link RagQuestionNormalizer#normalize} contract as
 * implemented on top of {@code Tokenizer.foldForMatching} (NFC, NFD
 * mark-strip, đ→d, Locale.ROOT lowercase) plus the run-collapsing replaceAll.
 */
class RagQuestionNormalizerTest {

    @Test
    void nullAndBlankInputsShareOneResult() {
        assertEquals("", RagQuestionNormalizer.normalize(null));
        assertEquals("", RagQuestionNormalizer.normalize(""));
        assertEquals("", RagQuestionNormalizer.normalize("   "));
        assertEquals("", RagQuestionNormalizer.normalize(" \t\r\n "));
    }

    @Test
    void keepsLettersAndNumbersAndFoldsCase() {
        assertEquals("hello world 123", RagQuestionNormalizer.normalize("Hello World 123"));
        // Vietnamese fold: NFD strip + đ/Đ mapped to d/D before lowercasing.
        assertEquals("banh dau ha noi 2024", RagQuestionNormalizer.normalize("Bánh Đậu Hà Nội 2024"));
    }

    @Test
    void collapsesOtherCharacterRunsToSingleSpaceBeforeTrim() {
        assertEquals("a b c", RagQuestionNormalizer.normalize("a...b??c"));
        assertEquals("hello world 7", RagQuestionNormalizer.normalize("!!  Hello  World 7!! "));
        assertEquals("it s ready", RagQuestionNormalizer.normalize("it's ready"));
    }
}

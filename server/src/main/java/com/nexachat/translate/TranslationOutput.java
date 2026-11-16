package com.nexachat.translate;

/** Validated provider output: the translated text and the detected source language code. */
public record TranslationOutput(String text, String sourceLang) {
}

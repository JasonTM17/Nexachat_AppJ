package com.nexachat.translate;

/**
 * Every configured provider tier failed or produced unusable output. The
 * service maps this to the typed {@code TRANSLATION_UNAVAILABLE} 503 so the
 * client can offer Retry.
 */
public class TranslationUnavailableException extends RuntimeException {

    public TranslationUnavailableException(String message) {
        super(message);
    }

    public TranslationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.nexachat.notification;

/**
 * Raised when an OAuth2 access token cannot be obtained. The message is safe to
 * log: it never carries the private key, the access token, or the token
 * endpoint response body.
 */
final class FcmTokenException extends RuntimeException {

    FcmTokenException(String message) {
        super(message);
    }
}

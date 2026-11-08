package com.nexachat.notification;

import java.util.logging.Logger;

/**
 * Redacted decision log for device-token synchronization.
 *
 * <p>Every message carries a reason code only. Token values, user identifiers,
 * phone numbers, and exception messages are deliberately never logged, so the
 * output is safe to read from logcat on a real device.
 */
final class DeviceTokenDiagnostics {

    private static final Logger LOGGER = Logger.getLogger("NexaChatDeviceToken");

    static final String NO_SESSION = "NO_SESSION";
    static final String FIREBASE_NOT_CONFIGURED = "FIREBASE_NOT_CONFIGURED";
    static final String TOKEN_NULL = "TOKEN_NULL";
    static final String TOKEN_BLANK = "TOKEN_BLANK";
    static final String REGISTERED = "REGISTERED";
    static final String UNREGISTERED = "UNREGISTERED";
    static final String EXECUTOR_MISSING = "EXECUTOR_MISSING";
    static final String TOKEN_CALLBACK_FAILED = "TOKEN_CALLBACK_FAILED";
    static final String TOKEN_STORED_SESSION = "TOKEN_STORED_SESSION";
    static final String TOKEN_STORED_NO_SESSION = "TOKEN_STORED_NO_SESSION";
    static final String SESSION_AVAILABLE = "SESSION_AVAILABLE";
    static final String SCHEDULE_FAILED = "SCHEDULE_FAILED";

    private DeviceTokenDiagnostics() { }

    static void info(String reasonCode) {
        LOGGER.info("device-token-sync: " + reasonCode);
    }

    static void warn(String reasonCode) {
        LOGGER.warning("device-token-sync: " + reasonCode);
    }

    /** Logs the HTTP status class (for example {@code HTTP_5XX}) without the body. */
    static void warnHttp(int status) {
        LOGGER.warning("device-token-sync: HTTP_" + (status / 100) + "XX");
    }

    /** Logs only the exception family; messages can carry request details. */
    static void warnError(Class<? extends Throwable> errorClass) {
        LOGGER.warning("device-token-sync: SYNC_ERROR_" + errorClass.getSimpleName());
    }
}

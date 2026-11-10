package com.nexachat.core.common;

import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Domain-facing error vocabulary produced by parsing the server's error
 * envelope ({@code {"error":{"code":…}}}). UI maps each variant to a string
 * resource and never parses {@code message}.
 *
 * <p>The three codeless variants are singletons. {@link #equals} for those is
 * type-based so each codeless variant retains singleton-style equality.
 */
public sealed interface ApiError
        permits ApiError.InvalidCredentials,
                ApiError.PhoneExists,
                ApiError.InvalidOrExpiredCode,
                ApiError.RateLimited,
                ApiError.Network,
                ApiError.MailUnavailable,
                ApiError.EmailAlreadyInUse,
                ApiError.RecoveryEmailInvalid,
                ApiError.Unknown {

    /** 401 {@code INVALID_CREDENTIALS} (also reused for {@code WRONG_PASSWORD}). */
    final class InvalidCredentials implements ApiError {
        public static final InvalidCredentials INSTANCE = new InvalidCredentials();

        private InvalidCredentials() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof InvalidCredentials;
        }

        @Override
        public int hashCode() {
            return InvalidCredentials.class.hashCode();
        }

        @Override
        public String toString() {
            return "InvalidCredentials";
        }
    }

    /** 409 {@code PHONE_EXISTS} on register. */
    final class PhoneExists implements ApiError {
        public static final PhoneExists INSTANCE = new PhoneExists();

        private PhoneExists() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PhoneExists;
        }

        @Override
        public int hashCode() {
            return PhoneExists.class.hashCode();
        }

        @Override
        public String toString() {
            return "PhoneExists";
        }
    }

    /** 503 {@code MAIL_UNAVAILABLE} — outbound email is disabled or unreachable. */
    final class MailUnavailable implements ApiError {
        public static final MailUnavailable INSTANCE = new MailUnavailable();

        private MailUnavailable() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MailUnavailable;
        }

        @Override
        public int hashCode() {
            return MailUnavailable.class.hashCode();
        }

        @Override
        public String toString() {
            return "MailUnavailable";
        }
    }

    /** 409 {@code EMAIL_ALREADY_IN_USE} on recovery email verify. */
    final class EmailAlreadyInUse implements ApiError {
        public static final EmailAlreadyInUse INSTANCE = new EmailAlreadyInUse();

        private EmailAlreadyInUse() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EmailAlreadyInUse;
        }

        @Override
        public int hashCode() {
            return EmailAlreadyInUse.class.hashCode();
        }

        @Override
        public String toString() {
            return "EmailAlreadyInUse";
        }
    }

    /** Server rejected the recovery email address ({@code VALIDATION_FAILED} on email routes). */
    final class RecoveryEmailInvalid implements ApiError {
        public static final RecoveryEmailInvalid INSTANCE = new RecoveryEmailInvalid();

        private RecoveryEmailInvalid() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RecoveryEmailInvalid;
        }

        @Override
        public int hashCode() {
            return RecoveryEmailInvalid.class.hashCode();
        }

        @Override
        public String toString() {
            return "RecoveryEmailInvalid";
        }
    }

    /** Wrong, expired, or already used email OTP ({@code INVALID_OR_EXPIRED_CODE}). */
    final class InvalidOrExpiredCode implements ApiError {
        public static final InvalidOrExpiredCode INSTANCE = new InvalidOrExpiredCode();

        private InvalidOrExpiredCode() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof InvalidOrExpiredCode;
        }

        @Override
        public int hashCode() {
            return InvalidOrExpiredCode.class.hashCode();
        }

        @Override
        public String toString() {
            return "InvalidOrExpiredCode";
        }
    }

    /** 429 / {@code RATE_LIMITED}. */
    final class RateLimited implements ApiError {
        public static final RateLimited INSTANCE = new RateLimited();

        private RateLimited() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RateLimited;
        }

        @Override
        public int hashCode() {
            return RateLimited.class.hashCode();
        }

        @Override
        public String toString() {
            return "RateLimited";
        }
    }

    /** Transport failure — no HTTP response at all. */
    final class Network implements ApiError {
        @Nullable private final String message;

        public Network() {
            this(null);
        }

        public Network(@Nullable String message) {
            this.message = message;
        }

        @Nullable
        public String getMessage() {
            return message;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Network network)) {
                return false;
            }
            return Objects.equals(message, network.message);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(message);
        }

        @Override
        public String toString() {
            return "Network(message=" + message + ")";
        }
    }

    /** Anything else; keeps the raw server {@code code} for logging/debug. */
    final class Unknown implements ApiError {
        @Nullable private final String code;
        private final int httpStatus;

        public Unknown() {
            this(null, -1);
        }

        public Unknown(@Nullable String code) {
            this(code, -1);
        }

        public Unknown(@Nullable String code, int httpStatus) {
            this.code = code;
            this.httpStatus = httpStatus;
        }

        @Nullable
        public String getCode() {
            return code;
        }

        /** HTTP status when this error came from a response; -1 for local errors. */
        public int getHttpStatus() {
            return httpStatus;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Unknown unknown)) {
                return false;
            }
            return Objects.equals(code, unknown.code);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(code);
        }

        @Override
        public String toString() {
            return "Unknown(code=" + code + ")";
        }
    }

    static ApiError from(int httpStatus, @Nullable String serverCode) {
        if ("INVALID_CREDENTIALS".equals(serverCode) || "WRONG_PASSWORD".equals(serverCode)) {
            return InvalidCredentials.INSTANCE;
        }
        if ("PHONE_EXISTS".equals(serverCode)) {
            return PhoneExists.INSTANCE;
        }
        if ("MAIL_UNAVAILABLE".equals(serverCode)) {
            return MailUnavailable.INSTANCE;
        }
        if ("EMAIL_ALREADY_IN_USE".equals(serverCode)) {
            return EmailAlreadyInUse.INSTANCE;
        }
        if ("INVALID_OR_EXPIRED_CODE".equals(serverCode)) {
            return InvalidOrExpiredCode.INSTANCE;
        }
        if ("RATE_LIMITED".equals(serverCode) || httpStatus == 429) {
            return RateLimited.INSTANCE;
        }
        return new Unknown(serverCode, httpStatus);
    }
}

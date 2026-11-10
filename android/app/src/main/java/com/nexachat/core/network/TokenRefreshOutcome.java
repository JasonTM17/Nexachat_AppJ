package com.nexachat.core.network;

import java.util.Objects;

/** Result of attempting to refresh the stored access token. */
public sealed interface TokenRefreshOutcome
        permits TokenRefreshOutcome.FreshToken,
                TokenRefreshOutcome.Rejected,
                TokenRefreshOutcome.TransientFailure,
                TokenRefreshOutcome.NoRefreshToken,
                TokenRefreshOutcome.RecoveryRequired {

    /** A usable access token exists, either freshly rotated or already current. */
    final class FreshToken implements TokenRefreshOutcome {
        private final String accessToken;

        public FreshToken(String accessToken) {
            this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        }

        public String getAccessToken() {
            return accessToken;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FreshToken freshToken)) {
                return false;
            }
            return accessToken.equals(freshToken.accessToken);
        }

        @Override
        public int hashCode() {
            return accessToken.hashCode();
        }

        @Override
        public String toString() {
            return "FreshToken";
        }
    }

    /** The refresh token itself was rejected and the local session was cleared. */
    final class Rejected implements TokenRefreshOutcome {
        public static final Rejected INSTANCE = new Rejected();

        private Rejected() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Rejected;
        }

        @Override
        public int hashCode() {
            return Rejected.class.hashCode();
        }

        @Override
        public String toString() {
            return "Rejected";
        }
    }

    /** A temporary failure occurred; the stored tokens remain available for retry. */
    final class TransientFailure implements TokenRefreshOutcome {
        public static final TransientFailure INSTANCE = new TransientFailure();

        private TransientFailure() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TransientFailure;
        }

        @Override
        public int hashCode() {
            return TransientFailure.class.hashCode();
        }

        @Override
        public String toString() {
            return "TransientFailure";
        }
    }

    /**
     * Server recovery is unavailable for a quarantined rotation; local session
     * data remains until explicit sign-in again.
     */
    final class RecoveryRequired implements TokenRefreshOutcome {
        public static final RecoveryRequired INSTANCE = new RecoveryRequired();

        private RecoveryRequired() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RecoveryRequired;
        }

        @Override
        public int hashCode() {
            return RecoveryRequired.class.hashCode();
        }

        @Override
        public String toString() {
            return "RecoveryRequired";
        }
    }

    /** No refresh token is stored. */
    final class NoRefreshToken implements TokenRefreshOutcome {
        public static final NoRefreshToken INSTANCE = new NoRefreshToken();

        private NoRefreshToken() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NoRefreshToken;
        }

        @Override
        public int hashCode() {
            return NoRefreshToken.class.hashCode();
        }

        @Override
        public String toString() {
            return "NoRefreshToken";
        }
    }
}

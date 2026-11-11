package com.nexachat.feature.auth;

/** One-shot navigation from splash after session restore. */
public sealed interface SplashNavEvent
        permits SplashNavEvent.ToLogin, SplashNavEvent.ToInbox {

    final class ToLogin implements SplashNavEvent {
        private final boolean sessionExpired;

        public ToLogin(boolean sessionExpired) {
            this.sessionExpired = sessionExpired;
        }

        public boolean showSessionExpiredMessage() {
            return sessionExpired;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ToLogin login && sessionExpired == login.sessionExpired;
        }

        @Override
        public int hashCode() {
            return Boolean.hashCode(sessionExpired);
        }

        @Override
        public String toString() {
            return "ToLogin(sessionExpired=" + sessionExpired + ")";
        }
    }

    final class ToInbox implements SplashNavEvent {
        public static final ToInbox INSTANCE = new ToInbox();

        private ToInbox() {}

        @Override
        public String toString() {
            return "ToInbox";
        }
    }
}

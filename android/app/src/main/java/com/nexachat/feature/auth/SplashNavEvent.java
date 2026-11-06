package com.nexachat.feature.auth;

/** One-shot navigation from splash after session restore. */
public sealed interface SplashNavEvent
        permits SplashNavEvent.ToLogin, SplashNavEvent.ToInbox {

    final class ToLogin implements SplashNavEvent {
        public static final ToLogin INSTANCE = new ToLogin();

        private ToLogin() {}

        @Override
        public String toString() {
            return "ToLogin";
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

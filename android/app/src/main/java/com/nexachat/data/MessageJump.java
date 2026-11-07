package com.nexachat.data;

import com.nexachat.core.common.ApiError;

/** Outcome of paging history back to a requested message. */
public sealed interface MessageJump permits MessageJump.Found, MessageJump.NotFound, MessageJump.Failed {

    /** The message is stored, so the list can scroll to it. */
    final class Found implements MessageJump {
        public static final Found INSTANCE = new Found();

        private Found() {}

        @Override
        public String toString() {
            return "Found";
        }
    }

    /** Not reachable within the page budget. */
    final class NotFound implements MessageJump {
        public static final NotFound INSTANCE = new NotFound();

        private NotFound() {}

        @Override
        public String toString() {
            return "NotFound";
        }
    }

    /** A page fetch failed. */
    record Failed(ApiError error) implements MessageJump {}
}

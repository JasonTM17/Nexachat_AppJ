package com.nexachat.data;

import com.nexachat.core.common.ApiError;
import java.util.Objects;

/** Outcome of paging history back to a message the user asked to see. */
public sealed interface MessageJump permits MessageJump.FoundValue, MessageJump.NotFoundValue, MessageJump.Failed {
    /** The message is stored, so the list can scroll to it. */
    MessageJump Found = FoundValue.INSTANCE;
    /** The message is gone or older than the page budget. */
    MessageJump NotFound = NotFoundValue.INSTANCE;

    final class FoundValue implements MessageJump {
        private static final FoundValue INSTANCE = new FoundValue();
        private FoundValue() {}
    }
    final class NotFoundValue implements MessageJump {
        private static final NotFoundValue INSTANCE = new NotFoundValue();
        private NotFoundValue() {}
    }
    final class Failed implements MessageJump {
        private final ApiError error;
        /** A page fetch failed; the caller can surface the error and retry. */
        public Failed(ApiError error) { this.error = error; }
        public ApiError getError() { return error; }

        @Override public boolean equals(Object other) {
            return other instanceof Failed that && Objects.equals(error, that.error);
        }

        @Override public int hashCode() { return Objects.hash(error); }
    }
}

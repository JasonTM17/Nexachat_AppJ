package com.nexachat.feature.group;

/** One-shot navigation from group detail. */
public sealed interface GroupDetailEvent permits GroupDetailEvent.ExitToInbox {

    /** Leave succeeded — the nav host returns to the inbox. */
    final class ExitToInbox implements GroupDetailEvent {
        public static final ExitToInbox INSTANCE = new ExitToInbox();

        private ExitToInbox() {}

        @Override
        public String toString() {
            return "ExitToInbox";
        }
    }
}

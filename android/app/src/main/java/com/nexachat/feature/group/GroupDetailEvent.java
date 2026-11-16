package com.nexachat.feature.group;

import androidx.annotation.Nullable;

/** One-shot navigation from group detail. */
public sealed interface GroupDetailEvent
        permits GroupDetailEvent.ExitToInbox, GroupDetailEvent.PreferenceFeedback {

    /** Leave succeeded — the nav host returns to the inbox. */
    final class ExitToInbox implements GroupDetailEvent {
        public static final ExitToInbox INSTANCE = new ExitToInbox();

        private ExitToInbox() {}

        @Override
        public String toString() {
            return "ExitToInbox";
        }
    }

    /** A conversation preference changed; the fragment confirms it with an optional undo. */
    final class PreferenceFeedback implements GroupDetailEvent {
        private final boolean muted;
        @Nullable private final Runnable undo;

        public PreferenceFeedback(boolean muted, @Nullable Runnable undo) {
            this.muted = muted;
            this.undo = undo;
        }

        public boolean isMuted() {
            return muted;
        }

        @Nullable
        public Runnable getUndo() {
            return undo;
        }

        @Override
        public String toString() {
            return "PreferenceFeedback(muted=" + muted + ")";
        }
    }
}

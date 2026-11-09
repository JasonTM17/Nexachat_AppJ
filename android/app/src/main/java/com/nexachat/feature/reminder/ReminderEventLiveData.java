package com.nexachat.feature.reminder;

import androidx.lifecycle.MutableLiveData;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot coordinator failures (delete) shared with the reminder fragment. */
final class ReminderEventLiveData
        extends MutableLiveData<ReminderEventLiveData.Event> {

    private long sequence;

    void emit(ReminderEditorError error) {
        setValue(new Event(++sequence, error));
    }

    static final class Event {
        private final long sequence;
        private final ReminderEditorError error;
        private final AtomicBoolean consumed = new AtomicBoolean();

        Event(long sequence, ReminderEditorError error) {
            this.sequence = sequence;
            this.error = error;
        }

        long getSequence() {
            return sequence;
        }

        ReminderEditorError consume() {
            return consumed.compareAndSet(false, true) ? error : null;
        }
    }
}

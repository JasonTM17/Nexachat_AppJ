package com.nexachat.feature.scheduled;

import androidx.lifecycle.MutableLiveData;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot row-action failures (cancel) shared with the scheduled list sheet. */
final class ScheduledMessageEventLiveData
        extends MutableLiveData<ScheduledMessageEventLiveData.Event> {

    private long sequence;

    void emit(ScheduledMessageEditorError error) {
        setValue(new Event(++sequence, error));
    }

    static final class Event {
        private final long sequence;
        private final ScheduledMessageEditorError error;
        private final AtomicBoolean consumed = new AtomicBoolean();

        Event(long sequence, ScheduledMessageEditorError error) {
            this.sequence = sequence;
            this.error = error;
        }

        long getSequence() {
            return sequence;
        }

        ScheduledMessageEditorError consume() {
            return consumed.compareAndSet(false, true) ? error : null;
        }
    }
}

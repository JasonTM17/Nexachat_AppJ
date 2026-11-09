package com.nexachat.feature.poll;

import androidx.lifecycle.MutableLiveData;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot poll failures shared with the poll fragment. */
final class PollEventLiveData extends MutableLiveData<PollEventLiveData.Event> {

    private long sequence;

    void emit(PollActionError error) {
        setValue(new Event(++sequence, error));
    }

    static final class Event {
        private final long sequence;
        private final PollActionError error;
        private final AtomicBoolean consumed = new AtomicBoolean();

        Event(long sequence, PollActionError error) {
            this.sequence = sequence;
            this.error = error;
        }

        long getSequence() {
            return sequence;
        }

        PollActionError consume() {
            return consumed.compareAndSet(false, true) ? error : null;
        }
    }
}

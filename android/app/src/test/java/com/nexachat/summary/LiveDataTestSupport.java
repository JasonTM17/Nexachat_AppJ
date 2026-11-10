package com.nexachat.summary;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Synchronous LiveData waits for JVM summary tests (no Thread.sleep polling). */
final class LiveDataTestSupport {
    private LiveDataTestSupport() {}

    static SummaryUiState awaitSummaryState(
            LiveData<SummaryUiState> liveData,
            SummaryUiState.Status status,
            String summaryId) throws InterruptedException {
        SummaryUiState current = liveData.getValue();
        if (matchesSummaryState(current, status, summaryId)) {
            return current;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<SummaryUiState> captured = new AtomicReference<>();
        Observer<SummaryUiState> observer = value -> {
            if (matchesSummaryState(value, status, summaryId)) {
                captured.set(value);
                latch.countDown();
            }
        };
        liveData.observeForever(observer);
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                SummaryUiState last = liveData.getValue();
                throw new AssertionError("Timed out waiting for " + status + ", last="
                        + (last == null ? null : last.getStatus()));
            }
            return captured.get();
        } finally {
            liveData.removeObserver(observer);
        }
    }

    static void awaitBoolean(LiveData<Boolean> liveData, boolean expected) throws InterruptedException {
        if (Boolean.valueOf(expected).equals(liveData.getValue())) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Observer<Boolean> observer = value -> {
            if (Boolean.valueOf(expected).equals(value)) {
                latch.countDown();
            }
        };
        liveData.observeForever(observer);
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Consent did not become " + expected);
            }
        } finally {
            liveData.removeObserver(observer);
        }
    }

    static void awaitError(LiveData<SummaryError> liveData, SummaryError expected)
            throws InterruptedException {
        if (expected == liveData.getValue()) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Observer<SummaryError> observer = value -> {
            if (expected == value) {
                latch.countDown();
            }
        };
        liveData.observeForever(observer);
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Consent error did not become " + expected);
            }
        } finally {
            liveData.removeObserver(observer);
        }
    }

    private static boolean matchesSummaryState(
            SummaryUiState value,
            SummaryUiState.Status status,
            String summaryId) {
        return value != null
                && value.getStatus() == status
                && (summaryId == null
                || (value.getSummary() != null && summaryId.equals(value.getSummary().getSummaryId())));
    }
}

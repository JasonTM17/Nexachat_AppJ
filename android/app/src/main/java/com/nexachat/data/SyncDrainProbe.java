package com.nexachat.data;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.nexachat.core.network.dto.SyncResponse;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug-gated timing seam for the v2 sync drain (issue #266, phase 0), and
 * holder of the drain-active window the inbox reconcile consults to defer
 * prune work while a drain holds the cache (issue #266, phase 1.5).
 *
 * <p>Diagnostics stay dormant by default: every timing hook first checks a
 * single volatile gate that the default {@link LineSink} answers false unless
 * the logcat tag override was flipped on-device with {@code adb shell
 * setprop log.tag.SyncDrain DEBUG}. While dormant the drain behaves exactly
 * as before — no diagnostic counter moves and no line is emitted. Once
 * enabled, each drain prints one line per page and one totals line, all
 * under the {@code SyncDrain} tag:
 *
 * <pre>
 * page #1 wm=100 rows=50 reactions=0 next=100 fetchMs=203.4 applyMs=388.1 guardMs=11.0 deltaMs=344.9 restarts=0
 * drain pages=60 wallMs=41233.0 fetchMs=8120.4 applyMs=29800.2 guardMs=670.1 deltaMs=28010.7 restarts=2 pending=true inbox=118 messages=97 inboxIdle=0 messagesIdle=0
 * </pre>
 *
 * <p>The phase-1.5 suppression state is product behavior and is never gated
 * by the sink: {@link #drainActive} opens and closes with every v2 drain,
 * {@link #onPruneSuppressed()} counts reconcile prunes deferred while it was
 * open, and {@link #onPrunesDeferredDrainEnd} — optional wiring in the
 * {@code onFlightJoined} seam style — runs once at the end of a drain that
 * deferred prunes so the repository can re-run the skipped reconcile on its
 * normal trigger path. None of this changes the drain's own behavior, and no
 * line's format changes: the deferred-prune counter never appears in an
 * emitted line.
 *
 * <p>Honors the #264 writer-transaction contract: {@link #pageGuard} and
 * {@link #pageDelta} run inside the held writer transaction and touch plain
 * memory only — never logging, never a Room or Rx statement. Lines are
 * emitted from the drain thread outside every transaction.
 */
final class SyncDrainProbe {
    static final String LOG_TAG = "SyncDrain";

    /** Emits one formatted drain diagnostics line. */
    interface LineSink {
        /** Checked once per drain; {@code false} keeps the whole probe dormant. */
        boolean wantsLines();

        void accept(String line);
    }

    /** Production sink: {@code D/SyncDrain} logcat lines behind the standard tag override. */
    static final LineSink LOGCAT = new LineSink() {
        @Override public boolean wantsLines() {
            try {
                return android.util.Log.isLoggable(LOG_TAG, android.util.Log.DEBUG);
            } catch (RuntimeException jvmUnitTests) {
                // android.jar's unmocked stub in JVM unit tests: the probe stays dormant.
                return false;
            }
        }

        @Override public void accept(String line) {
            android.util.Log.d(LOG_TAG, line);
        }
    };

    /** Swappable in the {@code onFlightJoined} seam style; tests install a recording sink. */
    @VisibleForTesting volatile LineSink sink = LOGCAT;

    // --- per-drain state: written by the single drain thread, reset by beginDrain ---
    volatile boolean recording;
    volatile boolean drainActive;
    volatile int pages;
    volatile int restarts;
    volatile long fetchNanos;
    volatile long applyNanos;
    volatile long guardNanos;
    volatile long deltaNanos;
    /** Current page's guard/delta spans; written inside the page transaction. */
    volatile long pageGuardNanos;
    volatile long pageDeltaNanos;
    @Nullable volatile String drainErrorClass;

    // --- observer state: Room invalidation/query threads write these ---
    private volatile boolean observerArmed;
    final AtomicInteger inboxDuringDrain = new AtomicInteger();
    final AtomicInteger messagesDuringDrain = new AtomicInteger();
    final AtomicLong inboxIdle = new AtomicLong();
    final AtomicLong messagesIdle = new AtomicLong();

    // --- prune suppression state (issue #266 phase 1.5): product behavior, never gated by the sink ---
    /** Reconcile prune sites deferred during the drain window now closing. */
    final AtomicInteger suppressedPrunes = new AtomicInteger();
    /**
     * Fired once at the end of a drain that deferred prunes, on the drain
     * thread outside every transaction, so the repository can re-run the
     * skipped reconcile work on its normal trigger path. Optional wiring in
     * the {@code onFlightJoined} seam style; the default is a no-op.
     */
    @VisibleForTesting volatile Runnable onPrunesDeferredDrainEnd = () -> {};

    boolean recording() { return recording; }

    /** The drain-active signal: true across the whole {@code SyncV2Consumer.sync} call. */
    boolean isDrainActive() { return drainActive; }

    /**
     * Records one prune site the reconcile deferred because a drain held the
     * cache window. Plain memory only — safe on any thread and inside the
     * suppression decision path (issue #264: no Rx, no Room statements).
     */
    void onPruneSuppressed() { suppressedPrunes.incrementAndGet(); }

    /** Opens the drain window, resets the per-drain counters and re-checks the sink gate once. */
    void beginDrain() {
        recording = sink.wantsLines();
        drainErrorClass = null;
        pages = 0;
        restarts = 0;
        fetchNanos = 0L;
        applyNanos = 0L;
        guardNanos = 0L;
        deltaNanos = 0L;
        pageGuardNanos = 0L;
        pageDeltaNanos = 0L;
        inboxDuringDrain.set(0);
        messagesDuringDrain.set(0);
        suppressedPrunes.set(0);
        if (recording) observerArmed = true;
        drainActive = true;
    }

    /** Per-page record; called on the drain thread after the page transaction returned. */
    void onPage(int requested, SyncResponse response, int nextSize, long pageFetchNanos,
            long pageApplyNanos) {
        if (!recording) return;
        pages++;
        fetchNanos += pageFetchNanos;
        applyNanos += pageApplyNanos;
        int changes = 0;
        int reactionUpdates = 0;
        for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
            if (delta.getChanges() != null) changes += delta.getChanges().size();
            if (delta.getReactionUpdates() != null) reactionUpdates += delta.getReactionUpdates().size();
        }
        emit(format("page #%d wm=%d rows=%d reactions=%d next=%d fetchMs=%.1f applyMs=%.1f"
                        + " guardMs=%.1f deltaMs=%.1f restarts=%d",
                pages, requested, changes, reactionUpdates, nextSize,
                ms(pageFetchNanos), ms(pageApplyNanos), ms(pageGuardNanos), ms(pageDeltaNanos),
                restarts));
        pageGuardNanos = 0L;
        pageDeltaNanos = 0L;
    }

    /**
     * Pre-commit guard span of the page transaction; plain memory only, so it
     * stays safe inside the held writer (issue #264). A {@code mismatch} is
     * one aborted page — the next drain re-walks that conversation from
     * cursor zero.
     */
    void pageGuard(long nanos, boolean mismatch) {
        if (!recording) return;
        pageGuardNanos = nanos;
        guardNanos += nanos;
        if (mismatch) restarts++;
    }

    /** Delta-loop span of the page transaction; plain memory only (issue #264). */
    void pageDelta(long nanos) {
        if (!recording) return;
        pageDeltaNanos = nanos;
        deltaNanos += nanos;
    }

    /** Marks a drain that ended via an exception; class name only, never the message. */
    void drainError(Throwable error) {
        if (!recording) return;
        drainErrorClass = error.getClass().getName();
    }

    /**
     * Closes the drain window, re-triggers the deferred reconcile when prunes
     * were suppressed during the window, and emits the totals line; drain
     * thread, outside transactions. The re-trigger reads the counter before
     * the window closes so a suppression racing the close still counts toward
     * this drain's decision (a miss merely falls back to the next normal
     * trigger — the suppression itself already happened).
     */
    void endDrain(boolean hasPendingContinuation, long wallNanos) {
        boolean prunesDeferred = suppressedPrunes.get() > 0;
        drainActive = false;
        if (prunesDeferred) onPrunesDeferredDrainEnd.run();
        if (!recording) return;
        String error = drainErrorClass != null ? " error=" + drainErrorClass : "";
        emit(format("drain%s pages=%d wallMs=%.1f fetchMs=%.1f applyMs=%.1f guardMs=%.1f"
                        + " deltaMs=%.1f restarts=%d pending=%b inbox=%d messages=%d"
                        + " inboxIdle=%d messagesIdle=%d",
                error, pages, ms(wallNanos), ms(fetchNanos), ms(applyNanos), ms(guardNanos),
                ms(deltaNanos), restarts, hasPendingContinuation, inboxDuringDrain.get(),
                messagesDuringDrain.get(), inboxIdle.get(), messagesIdle.get()));
    }

    /** {@code inboxFlow} emission; called on Room's invalidation/query threads. */
    void onInboxEmission() {
        if (!observerArmed) return;
        if (drainActive) inboxDuringDrain.incrementAndGet();
        else inboxIdle.incrementAndGet();
    }

    /** {@code messagesFlow} emission; called on Room's invalidation/query threads. */
    void onMessagesEmission() {
        if (!observerArmed) return;
        if (drainActive) messagesDuringDrain.incrementAndGet();
        else messagesIdle.incrementAndGet();
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    private void emit(String line) {
        sink.accept(line);
    }
}

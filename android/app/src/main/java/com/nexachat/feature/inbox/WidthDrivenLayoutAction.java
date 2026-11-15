package com.nexachat.feature.inbox;

/**
 * Retries a width-dependent layout rule until the view reports a real
 * measured width. A runnable queued with {@code View.post} can execute
 * before the first layout pass, when the width still reads zero; pairing
 * the post with an {@code OnLayoutChangeListener} and routing both through
 * this action makes the rule re-run on every width change instead of
 * silently giving up.
 */
final class WidthDrivenLayoutAction {

    /** Supplies the current measured width in pixels. */
    interface WidthSupplier {
        int get();
    }

    /** Applies the layout rule for a real measured width. */
    interface WidthApplier {
        void apply(int widthPx);
    }

    private final WidthSupplier widthSupplier;
    private final WidthApplier applier;

    WidthDrivenLayoutAction(WidthSupplier widthSupplier, WidthApplier applier) {
        this.widthSupplier = widthSupplier;
        this.applier = applier;
    }

    /**
     * Runs the applier once the measured width is real. A zero (or negative)
     * width is a no-op so a later layout pass can retry; the applier decides
     * whether anything actually changes, which keeps repeated runs from
     * looping layouts.
     *
     * @return whether the applier ran.
     */
    boolean run() {
        int width = widthSupplier.get();
        if (width <= 0) {
            return false;
        }
        applier.apply(width);
        return true;
    }
}

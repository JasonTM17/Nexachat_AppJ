package com.nexachat.core.ui;

/** Shared snackbar timings for actions the user must be able to reach. */
public final class SnackbarDurations {

    /**
     * Undo windows need more than Snackbar.LENGTH_LONG (~2.75 s): the archive
     * Undo action was unreachably short on real devices, so snacks carrying an
     * Undo action stay up five seconds instead.
     */
    public static final int UNDO_MS = 5000;

    private SnackbarDurations() {}
}

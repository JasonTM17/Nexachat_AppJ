package com.nexachat.feature.ui;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.color.MaterialColors;

/**
 * Shared chrome for programmatic alert dialogs: a scrolling body so tall
 * forms never push the button bar off the dialog band, an IME-resized window
 * so fields stay visible above the keyboard, and an error-coloured positive
 * button so destructive confirmations read as destructive.
 */
public final class FormDialogs {

    private FormDialogs() {
    }

    /** Wraps a form body so it scrolls inside the dialog instead of overflowing. */
    @NonNull
    public static View scrollableBody(@NonNull View form) {
        ScrollView scroll = new ScrollView(form.getContext());
        scroll.addView(form, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * Lifts the dialog window above the IME by resizing the window instead of
     * padding the decor — decor padding pushed titles and button bars out of
     * the visible band.
     */
    public static void resizeAboveIme(@Nullable AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /**
     * Paints the positive button in the error colour for destructive
     * confirmations. Call on the dialog returned by {@code show()}.
     */
    @NonNull
    public static AlertDialog markDestructive(@NonNull AlertDialog dialog) {
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextColor(MaterialColors.getColor(
                    positive, com.google.android.material.R.attr.colorError));
        }
        return dialog;
    }
}

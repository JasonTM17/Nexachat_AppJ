package com.nexachat.feature.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.nexachat.R;

/**
 * Shared scaffold for every NexaChat bottom sheet: expanded on show, a capped
 * width on wide screens, and a window that resizes above the keyboard.
 */
public abstract class NexaBottomSheetFragment extends BottomSheetDialogFragment {

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(ignored -> configureSheet(dialog));
        return dialog;
    }

    protected void configureSheet(@NonNull BottomSheetDialog dialog) {
        BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setMaxWidth(getResources().getDimensionPixelSize(R.dimen.sheet_max_width));
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /** Lifts the sheet's content above the nav bar and the keyboard. */
    protected void applySheetInsets(@NonNull View root) {
        ImeInsets.applyBottomPadding(root);
    }
}

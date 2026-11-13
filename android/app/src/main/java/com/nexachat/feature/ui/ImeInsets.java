package com.nexachat.feature.ui;

import android.view.View;
import android.view.Window;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keyboard and system-bar padding for dialogs and bottom sheets. The activity
 * host already pads its own window; these windows do not inherit that padding.
 */
public final class ImeInsets {

    private ImeInsets() {
    }

    public static int bottomOffset(int baseBottom, int systemBarBottom, int imeBottom) {
        return baseBottom + Math.max(systemBarBottom, imeBottom);
    }

    public static void applyToDecor(@NonNull Window window) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        View decor = window.getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (view, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    bars.left,
                    bars.top,
                    bars.right,
                    bottomOffset(0, bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(decor);
    }

    public static void applyBottomPadding(@NonNull View target) {
        final int baseLeft = target.getPaddingLeft();
        final int baseTop = target.getPaddingTop();
        final int baseRight = target.getPaddingRight();
        final int baseBottom = target.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(target, (view, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    baseLeft,
                    baseTop,
                    baseRight,
                    bottomOffset(baseBottom, bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(target);
    }
}

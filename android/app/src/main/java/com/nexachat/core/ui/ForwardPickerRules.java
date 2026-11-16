package com.nexachat.core.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/**
 * Body of the forward-target sheet: loading, error, empty, or the match list.
 */
public final class ForwardPickerRules {

    public enum Body {
        LOADING,
        ERROR,
        EMPTY,
        LIST
    }

    private ForwardPickerRules() {}

    public static Body body(boolean loading, boolean hasError, int matchCount) {
        if (loading && matchCount == 0) {
            return Body.LOADING;
        }
        if (hasError && matchCount == 0) {
            return Body.ERROR;
        }
        if (matchCount == 0) {
            return Body.EMPTY;
        }
        return Body.LIST;
    }

    /**
     * A selection restored after recreation only stays when its id still names
     * a target in the current list; a stale id must not re-enable Send.
     */
    @Nullable
    public static String retainedSelectedId(
            @Nullable String selectedId,
            @NonNull List<String> targetIds) {
        if (selectedId == null) {
            return null;
        }
        return targetIds.contains(selectedId) ? selectedId : null;
    }
}

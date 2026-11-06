package com.nexachat.core.ui;

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
}

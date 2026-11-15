package com.nexachat.core.ui;

/** Group detail member list: a failed load must not look like an empty roster. */
public final class GroupDetailListRules {

    private GroupDetailListRules() {}

    public static boolean showLoadError(boolean loading, boolean membersEmpty, boolean hasError) {
        return !loading && membersEmpty && hasError;
    }

    /** "Every contact is a member" is only true once the contacts fetch has completed. */
    public static boolean showAddMemberEmptyState(boolean addableLoaded, boolean addableEmpty) {
        return addableLoaded && addableEmpty;
    }
}

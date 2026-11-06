package com.nexachat.core.ui;

/**
 * Create-group contact list: a failed load must not look like an empty address book.
 */
public final class CreateGroupListRules {

    private CreateGroupListRules() {}

    public static boolean showLoadError(boolean loading, boolean contactsEmpty, boolean hasError) {
        return !loading && contactsEmpty && hasError;
    }
}

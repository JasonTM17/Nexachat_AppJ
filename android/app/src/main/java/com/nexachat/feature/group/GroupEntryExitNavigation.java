package com.nexachat.feature.group;

import androidx.annotation.NonNull;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import com.nexachat.R;
import java.util.List;

/**
 * Where group detail goes after a successful leave.
 * A stack that already contains the inbox pops back to it.
 * A direct-entry stack has no inbox, so navigation replaces the graph with one inbox.
 */
public final class GroupEntryExitNavigation {

    private GroupEntryExitNavigation() {}

    public static void leave(@NonNull NavController navController) {
        if (navController.popBackStack(R.id.inboxFragment, false)) {
            return;
        }
        NavOptions options = new NavOptions.Builder()
                .setPopUpTo(R.id.nav_main, true)
                .setLaunchSingleTop(true)
                .build();
        navController.navigate(R.id.inboxFragment, null, options);
    }

    public static List<Integer> destinationsAfterLeave(@NonNull List<Integer> backStack) {
        int inboxIndex = -1;
        for (int index = backStack.size() - 1; index >= 0; index--) {
            Integer destination = backStack.get(index);
            if (destination != null && destination == R.id.inboxFragment) {
                inboxIndex = index;
                break;
            }
        }
        if (inboxIndex >= 0) {
            return List.copyOf(backStack.subList(0, inboxIndex + 1));
        }
        return List.of(R.id.inboxFragment);
    }
}

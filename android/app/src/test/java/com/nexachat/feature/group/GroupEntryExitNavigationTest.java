package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import com.nexachat.R;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class GroupEntryExitNavigationTest {

    @Test
    public void stackWithInboxPopsToThatInbox() {
        List<Integer> stack = List.of(
                R.id.inboxFragment, R.id.chatFragment, R.id.groupDetailFragment);

        List<Integer> after = GroupEntryExitNavigation.destinationsAfterLeave(stack);

        assertEquals(List.of(R.id.inboxFragment), after);
        assertFalse(after.contains(R.id.chatFragment));
        assertFalse(after.contains(R.id.groupDetailFragment));
        assertEquals(1, count(after, R.id.inboxFragment));
    }

    @Test
    public void notificationStackWithoutInboxBecomesOneInbox() {
        List<Integer> stack = List.of(
                R.id.splashFragment, R.id.chatFragment, R.id.groupDetailFragment);
        assertFalse(stack.contains(R.id.inboxFragment));

        List<Integer> after = GroupEntryExitNavigation.destinationsAfterLeave(stack);

        assertEquals(List.of(R.id.inboxFragment), after);
        assertFalse(after.contains(R.id.splashFragment));
        assertFalse(after.contains(R.id.chatFragment));
        assertFalse(after.contains(R.id.groupDetailFragment));
    }

    @Test
    public void inviteStackWithoutInboxBecomesOneInbox() {
        List<Integer> stack = List.of(
                R.id.splashFragment,
                R.id.inviteJoinFragment,
                R.id.chatFragment,
                R.id.groupDetailFragment);
        assertFalse(stack.contains(R.id.inboxFragment));

        List<Integer> after = GroupEntryExitNavigation.destinationsAfterLeave(stack);

        assertEquals(List.of(R.id.inboxFragment), after);
        assertFalse(after.contains(R.id.inviteJoinFragment));
        assertFalse(after.contains(R.id.chatFragment));
        assertFalse(after.contains(R.id.groupDetailFragment));
    }

    @Test
    public void successfulInboxPopDoesNotNavigateAgain() {
        NavController nav = mock(NavController.class);
        when(nav.popBackStack(R.id.inboxFragment, false)).thenReturn(true);

        GroupEntryExitNavigation.leave(nav);

        verify(nav).popBackStack(R.id.inboxFragment, false);
        verify(nav, never()).navigate(anyInt(), any(), any());
    }

    @Test
    public void missedInboxPopNavigatesToOneInboxAndClearsTheGraph() {
        NavController nav = mock(NavController.class);
        when(nav.popBackStack(R.id.inboxFragment, false)).thenReturn(false);

        GroupEntryExitNavigation.leave(nav);

        ArgumentCaptor<NavOptions> options = ArgumentCaptor.forClass(NavOptions.class);
        verify(nav).navigate(eq(R.id.inboxFragment), isNull(), options.capture());
        assertEquals(R.id.nav_main, options.getValue().getPopUpToId());
        assertTrue(options.getValue().isPopUpToInclusive());
        assertTrue(options.getValue().shouldLaunchSingleTop());
    }

    @Test
    public void emptyNavControllerCannotPopToInboxWithoutGraph() {
        NavController nav = new NavController(mock(android.content.Context.class));
        assertFalse(nav.popBackStack(R.id.inboxFragment, false));
    }

    private static int count(List<Integer> destinations, int id) {
        int count = 0;
        for (Integer destination : destinations) {
            if (destination != null && destination == id) {
                count++;
            }
        }
        return count;
    }
}

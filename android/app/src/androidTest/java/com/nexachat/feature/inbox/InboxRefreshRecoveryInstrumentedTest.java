package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.TextView;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.nexachat.MainActivity;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.ConversationSummary;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class InboxRefreshRecoveryInstrumentedTest {
    @Test
    public void keepsCachedThreadsVisibleAndProvidesAnInlineRetry() {
        withInbox((inbox, state) -> {
            render(inbox, state.withRefreshFailure(new ApiError.Network("fixture")));
            View root = inbox.requireView();
            assertTrue(root.findViewById(R.id.conversationList).isShown());
            View retry = root.findViewById(R.id.inboxRefreshRetry);
            assertTrue(retry.isShown());
            assertEquals(root.getResources().getString(R.string.action_retry),
                    ((TextView) retry).getText().toString());
            render(inbox, state.withRefreshStarted());
            assertFalse(retry.isShown());
            render(inbox, state.withRefreshFailure(new ApiError.Network("fixture")));
            assertTrue(retry.isShown());
            render(inbox, state.withRefreshResult(false));
            assertFalse(retry.isShown());
            assertTrue(root.findViewById(R.id.conversationList).isShown());
        });
    }

    @Test
    public void anEmptyUnreadFilterDoesNotMaskARefreshFailure() {
        withInbox((inbox, state) -> {
            render(inbox, state.withSelectedFilter(InboxFilter.UNREAD)
                    .withRefreshFailure(new ApiError.Network("fixture")));
            View root = inbox.requireView();
            assertTrue("An incomplete empty filter needs error recovery",
                    root.findViewById(R.id.errorState).isShown());
            assertTrue(root.findViewById(R.id.retryButton).isShown());
            assertFalse(root.findViewById(R.id.emptyState).isShown());
        });
    }

    private static void withInbox(java.util.function.BiConsumer<InboxFragment, InboxUiState> action) {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host);
                host.getNavController().navigate(R.id.inboxFragment);
                host.getChildFragmentManager().executePendingTransactions();
                InboxFragment inbox = (InboxFragment) host.getChildFragmentManager().getPrimaryNavigationFragment();
                ConversationSummary cached = new ConversationSummary("cached", "GROUP", "NexaChat",
                        "Cached message", null, null, 1L, 0, null, null, null,
                        0L, null, null, false, null);
                action.accept(inbox, new InboxUiState(List.of(cached), null, false, null));
            });
        }
    }

    private static void render(InboxFragment fragment, InboxUiState state) {
        try {
            Method render = InboxFragment.class.getDeclaredMethod("render", InboxUiState.class);
            render.setAccessible(true);
            render.invoke(fragment, state);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}

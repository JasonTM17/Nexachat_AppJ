package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.view.View;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.nexachat.MainActivity;
import com.nexachat.R;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ChatGroupHeaderNavigationInstrumentedTest {
    @Test
    public void rapidGroupHeaderActivationsOpenOneDestination() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host);
                NavController nav = host.getNavController();
                Bundle args = new Bundle();
                args.putString("conversationId", "7b0c1f5e-1111-2222-3333-444455556666");
                nav.navigate(R.id.chatFragment, args);
                host.getChildFragmentManager().executePendingTransactions();
                ChatFragment chat = (ChatFragment) host.getChildFragmentManager().getPrimaryNavigationFragment();
                bindGroupHeader(chat);
                View toolbar = chat.requireView().findViewById(R.id.chatToolbar);
                assertTrue(toolbar.performClick());
                assertTrue(toolbar.performClick());
                assertEquals(R.id.groupDetailFragment, nav.getCurrentDestination().getId());
                assertTrue(nav.popBackStack());
                assertEquals("One Back must return to the original chat",
                        R.id.chatFragment, nav.getCurrentDestination().getId());
            });
        }
    }

    private static void bindGroupHeader(ChatFragment chat) {
        try {
            Method bind = ChatFragment.class.getDeclaredMethod("bindGroupInfoShortcut", boolean.class);
            bind.setAccessible(true);
            bind.invoke(chat, true);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}

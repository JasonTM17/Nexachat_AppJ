package com.nexachat.feature.invite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavGraph;
import androidx.navigation.NavGraphNavigator;
import androidx.navigation.NavInflater;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigator;
import androidx.navigation.NavigatorProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.nexachat.MainActivity;
import com.nexachat.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Proves the debug nav graph inflates and a custom-scheme invite link can open
 * {@code MainActivity}. An empty {@code invite_https_deep_link_pattern} used to
 * crash in {@code NavInflater} before the first screen.
 */
@RunWith(AndroidJUnit4.class)
public final class NavMainDeepLinkInstrumentedTest {
    private static final String TOKEN = "A".repeat(43);

    @Test
    public void navMainInflatesWithInviteDeepLinks() {
        Context context = ApplicationProvider.getApplicationContext();
        NavigatorProvider provider = new NavigatorProvider();
        provider.addNavigator(new NavGraphNavigator(provider));
        provider.addNavigator(new InflateOnlyFragmentNavigator());

        NavGraph graph = new NavInflater(context, provider).inflate(R.navigation.nav_main);
        assertEquals(R.id.nav_main, graph.getId());
        assertNotNull(graph.findNode(R.id.inviteJoinFragment));
        assertNotNull(graph.findNode(R.id.splashFragment));
    }

    @Test
    public void mainActivityLaunchesInviteDeepLinkWithoutCrashing() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("nexachat://join/" + TOKEN));
        intent.setClass(context, MainActivity.class);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertEquals("nexachat://join/" + TOKEN, activity.getIntent().getDataString());
                NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host);
                assertNotNull(host);
                NavController nav = host.getNavController();
                assertEquals(R.id.nav_main, nav.getGraph().getId());
                assertNotNull(nav.getGraph().findNode(R.id.inviteJoinFragment));
                NavDestination current = nav.getCurrentDestination();
                if (current == null || current.getId() != R.id.inviteJoinFragment) {
                    assertTrue(nav.handleDeepLink(activity.getIntent()));
                }
                assertNotNull(nav.getCurrentDestination());
                assertEquals(R.id.inviteJoinFragment, nav.getCurrentDestination().getId());
                Bundle args = nav.getCurrentBackStackEntry() == null
                        ? null : nav.getCurrentBackStackEntry().getArguments();
                assertNotNull(args);
                assertEquals(TOKEN, args.getString("token"));
            });
        }
    }

    @Navigator.Name("fragment")
    public static final class InflateOnlyFragmentNavigator extends Navigator<NavDestination> {
        public InflateOnlyFragmentNavigator() {
            super();
        }

        @Override
        @NonNull
        public NavDestination createDestination() {
            return new NavDestination(this);
        }

        @Override
        @Nullable
        public NavDestination navigate(
                @NonNull NavDestination destination,
                @Nullable Bundle args,
                @Nullable NavOptions navOptions,
                @Nullable Navigator.Extras navigatorExtras) {
            return null;
        }

        @Override
        public boolean popBackStack() {
            return false;
        }
    }
}

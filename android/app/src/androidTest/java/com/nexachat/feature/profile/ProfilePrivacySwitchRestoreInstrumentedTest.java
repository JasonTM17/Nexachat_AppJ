package com.nexachat.feature.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.accessibilityservice.AccessibilityService;
import android.app.UiAutomation;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.nexachat.MainActivity;
import com.nexachat.R;
import com.nexachat.data.SettingsRepository;
import com.nexachat.summary.AiConsentRepository;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The privacy switches render repository state, so a view-state restore must never
 * replay their listeners: same-value writes are deduplicated before the network, which
 * leaves only the invocation to observe. ProfileViewModel queues one disposable per
 * write call and AiConsentRepository bumps a generation once per refresh or update;
 * the consent prompt is detected by its title in the active window.
 */
@RunWith(AndroidJUnit4.class)
public final class ProfilePrivacySwitchRestoreInstrumentedTest {

    private static final long SETTLE_MS = 5_000;
    private static final long DIALOG_GRACE_MS = 1_000;

    @Test
    public void recreatedProfileDoesNotWriteBackRestoredSwitchState() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ProfileFragment profile = openProfile(scenario);
            seedRepositoryState(scenario, profile);
            awaitSwitchStates(scenario, profile, true);
            int writesBefore = pendingPrivacyWrites(scenario, profile);
            long consentMarksBefore = consentGenerations(scenario, profile);

            scenario.recreate();
            ProfileFragment restored = awaitPrimaryProfile(scenario);

            awaitSwitchStates(scenario, restored, true);
            assertEquals("View restoration must not replay presence/seen writes",
                    writesBefore, pendingPrivacyWrites(scenario, restored));
            assertEquals("A recreated view refreshes consent once and never writes",
                    consentMarksBefore + 1, consentGenerations(scenario, restored));
            assertNoConsentDialog();
        }
    }

    @Test
    public void returningFromDeleteAccountDoesNotWriteBackRestoredSwitchState() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ProfileFragment profile = openProfile(scenario);
            seedRepositoryState(scenario, profile);
            awaitSwitchStates(scenario, profile, true);
            int writesBefore = pendingPrivacyWrites(scenario, profile);
            long consentMarksBefore = consentGenerations(scenario, profile);

            navigate(scenario, R.id.deleteAccountFragment);
            navigateUp(scenario);
            ProfileFragment back = awaitPrimaryProfile(scenario);

            awaitSwitchStates(scenario, back, true);
            assertEquals("Back-stack view restoration must not replay presence/seen writes",
                    writesBefore, pendingPrivacyWrites(scenario, back));
            assertEquals("A returning view refreshes consent once and never writes",
                    consentMarksBefore + 1, consentGenerations(scenario, back));
            assertNoConsentDialog();
        }
    }

    @Test
    public void userToggleWritesExactlyOnce() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            ProfileFragment profile = openProfile(scenario);
            seedRepositoryState(scenario, profile);
            awaitSwitchStates(scenario, profile, true);

            int presenceBefore = pendingPrivacyWrites(scenario, profile);
            clickRow(scenario, profile, R.id.showPresenceRow);
            assertEquals("One presence toggle queues exactly one write",
                    presenceBefore + 1, pendingPrivacyWrites(scenario, profile));

            int seenBefore = pendingPrivacyWrites(scenario, profile);
            clickRow(scenario, profile, R.id.showSeenRow);
            assertEquals("One seen toggle queues exactly one write",
                    seenBefore + 1, pendingPrivacyWrites(scenario, profile));

            // Pinning the confirmed value keeps the optimistic off-state from rolling
            // back before the next toggle, so the second click must take the dialog path.
            pinConfirmedConsent(scenario, profile, false);
            long consentBefore = consentGenerations(scenario, profile);
            clickRow(scenario, profile, R.id.aiConsentRow);
            assertEquals("Disabling consent issues exactly one consent update",
                    consentBefore + 1, consentGenerations(scenario, profile));
            awaitSwitchState(scenario, profile, R.id.aiSummaryConsent, false);

            clickRow(scenario, profile, R.id.aiConsentRow);
            assertConsentDialogShown();
            dismissConsentDialog();
            assertEquals("Dismissing the consent prompt writes nothing",
                    consentBefore + 1, consentGenerations(scenario, profile));
        }
    }

    private static ProfileFragment openProfile(ActivityScenario<MainActivity> scenario) {
        navigate(scenario, R.id.profileFragment);
        return awaitPrimaryProfile(scenario);
    }

    private static void navigate(ActivityScenario<MainActivity> scenario, int destination) {
        scenario.onActivity(activity ->
                navHost(activity).getNavController().navigate(destination));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void navigateUp(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            boolean popped = navHost(activity).getNavController().popBackStack();
            if (!popped) {
                throw new AssertionError("popBackStack returned false");
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static ProfileFragment awaitPrimaryProfile(ActivityScenario<MainActivity> scenario) {
        AtomicReference<ProfileFragment> found = new AtomicReference<>();
        await("the profile destination did not appear", () -> {
            scenario.onActivity(activity -> {
                Fragment current = navHost(activity).getChildFragmentManager()
                        .getPrimaryNavigationFragment();
                found.set(current instanceof ProfileFragment ? (ProfileFragment) current : null);
            });
            return found.get() != null;
        }, SETTLE_MS);
        return found.get();
    }

    private static NavHostFragment navHost(MainActivity activity) {
        NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.nav_host);
        if (host == null) {
            throw new AssertionError("nav host missing");
        }
        return host;
    }

    /** The injected consent repository sits on the fragment; settings sits on the model. */
    private static AiConsentRepository consentRepository(ProfileFragment fragment) {
        return fragment.consentRepository;
    }

    private static SettingsRepository settingsRepository(ProfileFragment fragment) {
        return readField(viewModel(fragment), "settingsRepository");
    }

    private static Object viewModel(ProfileFragment fragment) {
        return readField(fragment, "viewModel");
    }

    /** Forces the three rows to render the on state regardless of any in-flight refresh. */
    private static void seedRepositoryState(
            ActivityScenario<MainActivity> scenario, ProfileFragment fragment) {
        scenario.onActivity(activity -> {
            AiConsentRepository consent = consentRepository(fragment);
            writeLongField(consent, "generation", Long.MAX_VALUE - 1_000);
            writeBooleanField(consent, "confirmedConsent", true);
            liveData(consent, "consent").setValue(Boolean.TRUE);
            SettingsRepository settings = settingsRepository(fragment);
            processor(settings, "mutableShowPresence").onNext(Boolean.TRUE);
            processor(settings, "mutableShowSeen").onNext(Boolean.TRUE);
        });
    }

    private static void pinConfirmedConsent(
            ActivityScenario<MainActivity> scenario, ProfileFragment fragment, boolean value) {
        scenario.onActivity(activity ->
                writeBooleanField(consentRepository(fragment), "confirmedConsent", value));
    }

    /** One disposable is queued per view-model write call; the count is the write seam. */
    private static int pendingPrivacyWrites(
            ActivityScenario<MainActivity> scenario, ProfileFragment fragment) {
        AtomicInteger writes = new AtomicInteger();
        scenario.onActivity(activity -> {
            CompositeDisposable disposables = readField(viewModel(fragment), "disposables");
            writes.set(disposables.size());
        });
        return writes.get();
    }

    /** The repository bumps its generation once per refresh and once per consent update. */
    private static long consentGenerations(
            ActivityScenario<MainActivity> scenario, ProfileFragment fragment) {
        AtomicReference<Long> generations = new AtomicReference<>();
        scenario.onActivity(activity -> generations.set(
                readLongField(consentRepository(fragment), "generation")));
        return generations.get();
    }

    private static void awaitSwitchStates(
            ActivityScenario<MainActivity> scenario, ProfileFragment fragment, boolean expected) {
        awaitSwitchState(scenario, fragment, R.id.showPresence, expected);
        awaitSwitchState(scenario, fragment, R.id.showSeen, expected);
        awaitSwitchState(scenario, fragment, R.id.aiSummaryConsent, expected);
    }

    private static void awaitSwitchState(
            ActivityScenario<MainActivity> scenario, ProfileFragment fragment,
            int switchId, boolean expected) {
        AtomicBoolean matches = new AtomicBoolean();
        await("switch " + switchId + " did not settle to " + expected, () -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                View root = fragment.getView();
                matches.set(root != null && checked(root, switchId) == expected);
            });
            return matches.get();
        }, SETTLE_MS);
    }

    private static boolean checked(View root, int switchId) {
        return ((CompoundButton) root.findViewById(switchId)).isChecked();
    }

    private static void clickRow(
            ActivityScenario<MainActivity> scenario, ProfileFragment fragment, int rowId) {
        scenario.onActivity(activity -> assertTrue("row click was not handled",
                fragment.requireView().findViewById(rowId).performClick()));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void assertNoConsentDialog() {
        AtomicBoolean shown = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + DIALOG_GRACE_MS;
        while (SystemClock.uptimeMillis() < deadline && !shown.get()) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            shown.set(shown.get() || consentDialogShowing());
            SystemClock.sleep(50);
        }
        assertFalse("State restoration must not open the AI-consent dialog", shown.get());
    }

    private static void assertConsentDialogShown() {
        await("the AI-consent dialog did not appear",
                ProfilePrivacySwitchRestoreInstrumentedTest::consentDialogShowing, SETTLE_MS);
    }

    private static void dismissConsentDialog() {
        uiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        await("the AI-consent dialog did not dismiss",
                () -> !consentDialogShowing(), SETTLE_MS);
    }

    private static boolean consentDialogShowing() {
        AccessibilityNodeInfo root = uiAutomation().getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(
                context.getString(R.string.dialog_ai_consent_title));
        boolean found = matches != null && !matches.isEmpty();
        root.recycle();
        return found;
    }

    private static UiAutomation uiAutomation() {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    private static void await(String description, BooleanSupplier condition, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (condition.getAsBoolean()) {
                return;
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                fail(description);
            }
            SystemClock.sleep(40);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static long readLongField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.getLong(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void writeLongField(Object target, String name, long value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.setLong(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void writeBooleanField(Object target, String name, boolean value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static MutableLiveData<Boolean> liveData(Object target, String name) {
        return (MutableLiveData<Boolean>) readField(target, name);
    }

    @SuppressWarnings("unchecked")
    private static BehaviorProcessor<Boolean> processor(Object target, String name) {
        return (BehaviorProcessor<Boolean>) readField(target, name);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // The field may live on a superclass; keep walking up.
            }
        }
        throw new AssertionError("no field " + name + " on " + type.getName());
    }
}

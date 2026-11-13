package com.nexachat.feature.auth;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.activity.OnBackPressedDispatcher;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import android.view.View;
import com.nexachat.databinding.FragmentCompleteProfileBinding;
import org.mockito.ArgumentCaptor;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavGraph;
import androidx.navigation.NavGraphNavigator;
import androidx.navigation.Navigator;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/** Runs the real Navigation controller with a no-UI destination navigator. */
public final class AuthJourneyBackStackTest {
    @Rule public final InstantTaskExecutorRule executor = new InstantTaskExecutorRule();
    private final Map<Bundle, Map<String, Object>> bundleValues = new java.util.IdentityHashMap<>();
    private MockedConstruction<Bundle> bundles;
    private MockedStatic<Log> logs;
    private MockedStatic<NavHostFragment> hosts;
    private NavController nav;

    @Before public void setUp() throws Exception {
        // JVM Android stubs have no Bundle implementation. Only platform storage is replaced.
        bundles = mockConstruction(Bundle.class, withSettings().defaultAnswer(call -> {
            Map<String, Object> values = bundleValues.computeIfAbsent(
                    (Bundle) call.getMock(), ignored -> new HashMap<>());
            String name = call.getMethod().getName();
            Object[] args = call.getArguments();
            if (name.equals("putAll")) { values.putAll(bundleValues.get((Bundle) args[0])); return null; }
            if (name.startsWith("put") && args.length == 2) { values.put((String) args[0], args[1]); return null; }
            if (name.equals("get") || name.startsWith("get") && args.length > 0 && args[0] instanceof String) {
                Object value = values.get(args[0]);
                return value != null ? value : args.length > 1 ? args[1] : null;
            }
            if (name.equals("containsKey")) return values.containsKey(args[0]);
            if (name.equals("isEmpty")) return values.isEmpty();
            if (name.equals("size")) return values.size();
            if (name.equals("keySet")) return values.keySet();
            if (name.equals("remove")) { values.remove(args[0]); return null; }
            return RETURNS_DEFAULTS.answer(call);
        }), (bundle, context) -> {
            Map<String, Object> values = new HashMap<>();
            if (!context.arguments().isEmpty() && context.arguments().get(0) instanceof Bundle original) {
                values.putAll(bundleValues.get(original));
            }
            bundleValues.put(bundle, values);
        });
        logs = mockStatic(Log.class);
        Context context = mock(Context.class, RETURNS_DEEP_STUBS);
        when(context.getResources().getResourceName(anyInt())).thenReturn("destination");
        nav = new NavController(context);
        NoUiNavigator navigator = new NoUiNavigator();
        nav.getNavigatorProvider().addNavigator(navigator);
        NavGraph graph = new NavGraph(new NavGraphNavigator(nav.getNavigatorProvider()));
        graph.setId(R.id.nav_main);
        for (int id : new int[] { R.id.loginFragment, R.id.registerFragment,
                R.id.completeProfileFragment, R.id.inboxFragment, R.id.inviteJoinFragment }) {
            NavDestination destination = navigator.createDestination();
            destination.setId(id);
            graph.addDestination(destination);
        }
        // Avoid javac resolving the optional serialization overload (not a project dependency).
        Field start = NavGraph.class.getDeclaredField("startDestId");
        start.setAccessible(true);
        start.setInt(graph, R.id.loginFragment);
        nav.setGraph(graph);
        hosts = mockStatic(NavHostFragment.class);
        hosts.when(() -> NavHostFragment.findNavController(any())).thenReturn(nav);
    }

    @After public void tearDown() {
        if (hosts != null) hosts.close();
        if (logs != null) logs.close();
        if (bundles != null) bundles.close();
    }

    @Test public void repeatedAuthSwitchesLeaveOnlyOneAuthEntry() {
        for (int i = 0; i < 4; i++) {
            nav.navigate(R.id.registerFragment, null, AuthNav.authTabSwitchOptions());
            nav.navigate(R.id.loginFragment, null, AuthNav.authTabSwitchOptions());
        }
        assertEquals(List.of(R.id.loginFragment), destinations());
        assertFalse(nav.popBackStack());
    }

    @Test public void loginCompletionRemovesAllAuthAncestors() throws Exception {
        switchToRegisterAndBack();
        invoke(new LoginFragment(), "onAuthEvent", AuthViewModel.Event.LoggedIn);
        assertOnlyAuthenticatedDestination(R.id.inboxFragment);
    }

    @Test public void registerCompletionRemovesAllAuthAncestors() throws Exception {
        switchToRegisterAndBack();
        nav.navigate(R.id.registerFragment, null, AuthNav.authTabSwitchOptions());
        invoke(new RegisterFragment(), "onAuthEvent", AuthViewModel.Event.LoggedIn);
        assertOnlyAuthenticatedDestination(R.id.inboxFragment);
    }

    @Test public void newGoogleLoginContinueRemovesOnboarding() throws Exception {
        completeGoogle(new LoginFragment());
        assertOnlyAuthenticatedDestination(R.id.inboxFragment);
    }

    @Test public void newGoogleRegisterContinueRemovesOnboarding() throws Exception {
        nav.navigate(R.id.registerFragment, null, AuthNav.authTabSwitchOptions());
        completeGoogle(new RegisterFragment());
        assertOnlyAuthenticatedDestination(R.id.inboxFragment);
    }

    private static final String INVITE_TOKEN = "A".repeat(43);

    @Test public void phoneLoginKeepsInviteDestination() throws Exception {
        LoginFragment form = new LoginFragment();
        setField(form, "returnInviteToken", INVITE_TOKEN);
        invoke(form, "onAuthEvent", AuthViewModel.Event.LoggedIn);
        assertInviteDestination();
    }

    @Test public void phoneRegisterKeepsInviteDestination() throws Exception {
        nav.navigate(R.id.registerFragment, null, AuthNav.authTabSwitchOptions());
        RegisterFragment form = new RegisterFragment();
        setField(form, "returnInviteToken", INVITE_TOKEN);
        invoke(form, "onAuthEvent", AuthViewModel.Event.LoggedIn);
        assertInviteDestination();
    }

    @Test public void newGoogleLoginCarriesInviteToOnboarding() throws Exception {
        openGoogleWithInvite(new LoginFragment());
        assertOnboardingInviteArgument();
    }

    @Test public void newGoogleRegisterCarriesInviteToOnboarding() throws Exception {
        nav.navigate(R.id.registerFragment, null, AuthNav.authTabSwitchOptions());
        openGoogleWithInvite(new RegisterFragment());
        assertOnboardingInviteArgument();
    }

    @Test public void onboardingBackReturnsToInviteAfterArgumentRecreation() throws Exception {
        openOnboardingWithInvite(INVITE_TOKEN);
        finishOnboarding(true);
        assertInviteDestination();
    }

    @Test public void onboardingContinueReturnsToInviteAfterArgumentRecreation() throws Exception {
        openOnboardingWithInvite(INVITE_TOKEN);
        finishOnboarding(false);
        assertInviteDestination();
    }

    @Test public void onboardingWithoutInviteBackOpensInbox() throws Exception {
        openOnboardingWithInvite(null);
        finishOnboarding(true);
        assertOnlyAuthenticatedDestination(R.id.inboxFragment);
    }

    @Test public void malformedInviteCannotBecomeAuthenticatedReturnDestination() throws Exception {
        openOnboardingWithInvite("invalid/token");
        finishOnboarding(false);
        assertOnlyAuthenticatedDestination(R.id.inboxFragment);
    }

    private void openGoogleWithInvite(Object form) throws Exception {
        setField(form, "returnInviteToken", INVITE_TOKEN);
        AuthViewModel vm = mock(AuthViewModel.class);
        when(vm.onCompleteProfileNavigationHandled()).thenReturn(true);
        setField(form, "viewModel", vm);
        invoke(form, "navigateToCompleteProfile");
    }

    private void assertOnboardingInviteArgument() {
        assertEquals(R.id.completeProfileFragment, nav.getCurrentDestination().getId());
        Bundle args = nav.getCurrentBackStackEntry().getArguments();
        assertNotNull("Onboarding must receive the return route", args);
        assertEquals(INVITE_TOKEN, args.getString("returnInviteToken"));
    }

    private void openOnboardingWithInvite(String token) {
        Bundle args = new Bundle();
        args.putString("returnInviteToken", token);
        nav.navigate(R.id.completeProfileFragment, args, AuthNav.signedInOptions());
    }

    private void assertInviteDestination() {
        assertEquals(R.id.inviteJoinFragment, nav.getCurrentDestination().getId());
        assertEquals(INVITE_TOKEN, nav.getCurrentBackStackEntry().getArguments().getString("token"));
        assertOnlyAuthenticatedDestination(R.id.inviteJoinFragment);
    }

    private void finishOnboarding(boolean back) throws Exception {
        // A new Fragment instance receives copied entry arguments, as after recreation.
        CompleteProfileFragment fragment = spy(new CompleteProfileFragment());
        fragment.setArguments(new Bundle(nav.getCurrentBackStackEntry().getArguments()));
        LifecycleOwner owner = mock(LifecycleOwner.class);
        LifecycleRegistry lifecycle = new LifecycleRegistry(owner);
        when(owner.getLifecycle()).thenReturn(lifecycle);
        lifecycle.setCurrentState(Lifecycle.State.RESUMED);
        doReturn(owner).when(fragment).getViewLifecycleOwner();
        FragmentActivity activity = mock(FragmentActivity.class);
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        when(activity.getOnBackPressedDispatcher()).thenReturn(dispatcher);
        doReturn(activity).when(fragment).requireActivity();

        var constructor = FragmentCompleteProfileBinding.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] views = java.util.Arrays.stream(constructor.getParameterTypes())
                .map(type -> mock(type)).toArray();
        FragmentCompleteProfileBinding binding = (FragmentCompleteProfileBinding) constructor.newInstance(views);
        setField(fragment, "binding", binding);
        CompleteProfileViewModel vm = mock(CompleteProfileViewModel.class);
        MutableLiveData<CompleteProfileViewModel.Event> events = new MutableLiveData<>();
        when(vm.getUiState()).thenReturn(new MutableLiveData<>());
        when(vm.getEvents()).thenReturn(events);
        doAnswer(call -> { events.setValue(CompleteProfileViewModel.Event.Continue); return null; })
                .when(vm).continueToInbox();
        try (var providers = mockConstruction(ViewModelProvider.class,
                (provider, context) -> when(provider.get(CompleteProfileViewModel.class)).thenReturn(vm))) {
            fragment.onViewCreated(mock(View.class), null);
            if (back) {
                dispatcher.onBackPressed();
                verify(vm, never()).continueToInbox();
            } else {
                ArgumentCaptor<View.OnClickListener> click = ArgumentCaptor.forClass(View.OnClickListener.class);
                verify(binding.completeProfileContinue).setOnClickListener(click.capture());
                click.getValue().onClick(binding.completeProfileContinue);
                verify(vm).continueToInbox();
            }
        }
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
    }

    private void completeGoogle(Object form) throws Exception {
        AuthViewModel vm = mock(AuthViewModel.class);
        when(vm.onCompleteProfileNavigationHandled()).thenReturn(true);
        setField(form, "viewModel", vm);
        invoke(form, "navigateToCompleteProfile");
        assertEquals(R.id.completeProfileFragment, nav.getCurrentDestination().getId());
        invoke(new CompleteProfileFragment(), "openSignedInDestination");
    }

    private void switchToRegisterAndBack() {
        nav.navigate(R.id.registerFragment, null, AuthNav.authTabSwitchOptions());
        nav.navigate(R.id.loginFragment, null, AuthNav.authTabSwitchOptions());
    }

    private void assertOnlyAuthenticatedDestination(int destination) {
        assertEquals(List.of(destination), destinations());
        assertFalse(nav.popBackStack());
        assertNull(nav.getCurrentDestination());
    }

    private List<Integer> destinations() {
        return nav.getCurrentBackStack().getValue().stream()
                .map(NavBackStackEntry::getDestination)
                .filter(destination -> !(destination instanceof NavGraph))
                .map(NavDestination::getId).toList();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                method.invoke(target, args);
                return;
            }
        }
        throw new AssertionError("Missing navigation handler: " + name);
    }

    @Navigator.Name("no-ui")
    public static final class NoUiNavigator extends Navigator<NavDestination> {
        @Override public NavDestination createDestination() { return new NavDestination(this); }
    }
}

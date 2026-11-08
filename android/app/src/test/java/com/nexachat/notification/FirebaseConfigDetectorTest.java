package com.nexachat.notification;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class FirebaseConfigDetectorTest {

    @Test
    public void defaultAppWithApplicationIdIsConfigured() {
        assertTrue(FirebaseConfigDetector.isDefaultAppConfigured(
                Collections.singletonList(app(FirebaseApp.DEFAULT_APP_NAME, "1:123:android:abc"))));
    }

    @Test
    public void nonDefaultAppIsNotConfigured() {
        assertFalse(FirebaseConfigDetector.isDefaultAppConfigured(
                Collections.singletonList(app("custom-app", "1:123:android:abc"))));
    }

    @Test
    public void blankApplicationIdIsNotConfigured() {
        assertFalse(FirebaseConfigDetector.isDefaultAppConfigured(
                Collections.singletonList(app(FirebaseApp.DEFAULT_APP_NAME, "   "))));
    }

    @Test
    public void missingApplicationIdIsNotConfigured() {
        assertFalse(FirebaseConfigDetector.isDefaultAppConfigured(
                Collections.singletonList(app(FirebaseApp.DEFAULT_APP_NAME, null))));
    }

    @Test
    public void emptyAppListIsNotConfigured() {
        assertFalse(FirebaseConfigDetector.isDefaultAppConfigured(Collections.emptyList()));
    }

    @Test
    public void configuredAppAfterOtherAppsIsDetected() {
        assertTrue(FirebaseConfigDetector.isDefaultAppConfigured(Arrays.asList(
                app("custom-app", "1:123:android:abc"),
                app(FirebaseApp.DEFAULT_APP_NAME, "1:456:android:def"))));
    }

    @Test
    public void nullAppEntryIsNotConfigured() {
        assertFalse(FirebaseConfigDetector.isDefaultApp(null));
        assertFalse(FirebaseConfigDetector.isDefaultAppConfigured(
                Collections.singletonList(null)));
    }

    @Test
    public void optionsFailureIsNotConfigured() {
        FirebaseApp app = mock(FirebaseApp.class);
        when(app.getName()).thenReturn(FirebaseApp.DEFAULT_APP_NAME);
        when(app.getOptions()).thenThrow(new IllegalStateException("firebase runtime absent"));

        assertFalse(FirebaseConfigDetector.isDefaultApp(app));
    }

    private static FirebaseApp app(String name, String applicationId) {
        FirebaseApp app = mock(FirebaseApp.class);
        FirebaseOptions options = mock(FirebaseOptions.class);
        when(app.getName()).thenReturn(name);
        when(app.getOptions()).thenReturn(options);
        when(options.getApplicationId()).thenReturn(applicationId);
        return app;
    }
}

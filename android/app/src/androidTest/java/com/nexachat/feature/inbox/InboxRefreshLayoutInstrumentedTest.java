package com.nexachat.feature.inbox;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.LocaleList;
import android.util.DisplayMetrics;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.android.material.theme.MaterialComponentsViewInflater;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.nexachat.R;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class InboxRefreshLayoutInstrumentedTest {
    @Test
    public void recoveryFitsNarrowScreensWithLargeTextInBothLanguagesAndThemes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (String language : new String[] {"en", "vi"}) {
                for (boolean night : new boolean[] {false, true}) {
                    Context context = themedContext(language, night);
                    View root = inflater(context).inflate(
                            R.layout.fragment_inbox, new FrameLayout(context), false);
                    View banner = root.findViewById(R.id.inboxRefreshError);
                    banner.setVisibility(View.VISIBLE);
                    TextView message = root.findViewById(R.id.inboxRefreshErrorMessage);
                    message.setText(R.string.error_network);
                    measure(root);
                    View retry = root.findViewById(R.id.inboxRefreshRetry);
                    View listHost = root.findViewById(R.id.swipeRefresh);
                    assertTrue("Retry keeps a 48dp touch target", retry.getHeight() >= 48);
                    assertTrue("Retry must fit inside the banner",
                            retry.getLeft() >= 0 && retry.getRight() <= banner.getWidth());
                    assertTrue("Error text must have a readable width", message.getWidth() >= 96);
                    assertTrue("All message lines must fit",
                            message.getHeight() >= message.getLayout().getHeight());
                    assertTrue("Banner must leave a usable list viewport", listHost.getHeight() >= 96);
                    capture(root, language + (night ? "-dark" : "-light") + "-320dp-200.png");
                }
            }
        });
    }

    @Test
    public void recordingDecorationIsExcludedFromAccessibility() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext("en", false);
            View composer = inflater(context).inflate(
                    R.layout.include_chat_composer_bar, new FrameLayout(context), false);
            View dot = composer.findViewById(R.id.recordingDot);
            assertTrue(dot.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        });
    }

    private static Context themedContext(String language, boolean night) {
        Context app = ApplicationProvider.getApplicationContext();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocales(new LocaleList(Locale.forLanguageTag(language)));
        config.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        config.fontScale = 2f;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return new ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_NexaChat_Views);
    }

    private static void measure(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 320, 640);
    }

    private static LayoutInflater inflater(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context).cloneInContext(context);
        MaterialComponentsViewInflater views = new MaterialComponentsViewInflater();
        inflater.setFactory2(new LayoutInflater.Factory2() {
            @Override
            public View onCreateView(View parent, String name, Context current, AttributeSet attrs) {
                return views.createView(parent, name, current, attrs, false, true, true, true);
            }

            @Override
            public View onCreateView(String name, Context current, AttributeSet attrs) {
                return onCreateView(null, name, current, attrs);
            }
        });
        return inflater;
    }

    private static void capture(View root, String name) {
        String run = InstrumentationRegistry.getArguments().getString("captureRun");
        if (run == null) return;
        Context app = ApplicationProvider.getApplicationContext();
        File directory = new File(app.getExternalFilesDir(null), "ux-refresh/" + run);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new AssertionError("Cannot create capture directory");
        }
        Bitmap bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (IOException failure) {
            throw new AssertionError(failure);
        } finally {
            bitmap.recycle();
        }
    }
}

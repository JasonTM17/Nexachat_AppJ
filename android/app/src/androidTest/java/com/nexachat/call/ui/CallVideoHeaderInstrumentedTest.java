package com.nexachat.call.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.os.LocaleList;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.nexachat.R;
import com.google.android.material.button.MaterialButton;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallVideoHeaderInstrumentedTest {
    @Test
    public void largeTextFitsBesidePreviewInPortraitAndLandscape() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (String language : new String[] {"en", "vi"}) {
                for (int theme : new int[] {Configuration.UI_MODE_NIGHT_NO,
                        Configuration.UI_MODE_NIGHT_YES}) {
                    checkHeader(language, theme, 320, 640);
                    checkHeader(language, theme, 640, 320);
                }
            }
        });
    }

    private static void checkHeader(String language, int theme, int width, int height) {
        Context app = ApplicationProvider.getApplicationContext();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocales(new LocaleList(Locale.forLanguageTag(language)));
        config.orientation = width < height
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        config.screenWidthDp = width;
        config.screenHeightDp = height;
        config.smallestScreenWidthDp = Math.min(width, height);
        config.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        config.fontScale = 2f;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | theme;
        Context context = new ContextThemeWrapper(
                app.createConfigurationContext(config), R.style.Theme_NexaChat_Call);
        View root = LayoutInflater.from(context).inflate(
                R.layout.activity_call, new FrameLayout(context), false);

        View header = root.findViewById(R.id.call_video_header);
        assertNotNull("Each orientation must have the video header", header);
        assertEquals("The header starts hidden before a video call connects",
                View.GONE, header.getVisibility());
        View preview = root.findViewById(R.id.call_local_preview);
        TextView name = header.findViewById(R.id.call_video_peer_name);
        TextView detail = header.findViewById(R.id.call_video_detail);
        name.setText("Nguyễn Phương Linh và tên hiển thị rất dài");
        header.setVisibility(View.VISIBLE);
        preview.setVisibility(View.VISIBLE);
        root.findViewById(R.id.call_camera).setVisibility(View.VISIBLE);
        root.findViewById(R.id.call_camera_switch).setVisibility(View.VISIBLE);
        String variant = language + " " + theme + " " + width + "x" + height;
        assertEquals(variant + " timer is not announced every second",
                View.ACCESSIBILITY_LIVE_REGION_NONE, detail.getAccessibilityLiveRegion());

        for (String text : new String[] {"02:16", context.getString(R.string.call_state_reconnecting)}) {
            detail.setText(text);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            assertEquals(variant + " gradient spans the top edge", width, header.getWidth());
            assertEquals(variant + " gradient starts at the top edge", 0, header.getTop());
            assertTrue(variant + " call details stay above the center",
                    detail.getBottom() <= height / 2);
            int textEnd = header.getLeft() + Math.max(name.getRight(), detail.getRight());
            assertTrue(variant + " text leaves a gap before the camera preview",
                    preview.getLeft() - textEnd >= 12);
            if (text.equals("02:16")) {
                assertEquals(variant + " duration stays on one line", 1, detail.getLineCount());
            }
            assertTrue(variant + " " + text + " is visible in full",
                    detail.getLayout().getHeight() <= detail.getHeight());
            int lastLine = detail.getLineCount() - 1;
            assertEquals(variant + " " + text + " reaches the last character",
                    text.length(), detail.getLayout().getLineEnd(lastLine));
            assertGradientContrast(variant, header, name, detail);
            assertCallControlGeometry(variant, root);
        }
    }

    private static void assertCallControlGeometry(String variant, View root) {
        View row = root.findViewById(R.id.call_controls_row);
        int previousRight = -8;
        for (int id : new int[] {R.id.call_mute, R.id.call_speaker, R.id.call_camera,
                R.id.call_camera_switch, R.id.call_hang_up}) {
            MaterialButton button = root.findViewById(id);
            assertNotNull(variant + " control has an icon", button.getIcon());
            assertTrue(variant + " control has a name", button.getContentDescription().length() > 0);
            assertTrue(variant + " minimum touch target", button.getWidth() >= 48 && button.getHeight() >= 48);
            assertTrue(variant + " neighboring targets are separated by 8dp", button.getLeft() - previousRight >= 8);
            assertTrue(variant + " control stays inside the row", button.getRight() <= row.getWidth());
            assertEquals(variant + " icons share the same vertical center", row.getHeight() / 2,
                    (button.getTop() + button.getBottom()) / 2);
            previousRight = button.getRight();
            ColorStateList background = button.getBackgroundTintList();
            double contrast = ColorUtils.calculateContrast(button.getIconTint().getDefaultColor(),
                    background.getColorForState(new int[] {android.R.attr.state_enabled}, background.getDefaultColor()));
            assertTrue(variant + " active control contrast " + contrast, contrast >= 4.5);
        }
    }

    private static void assertGradientContrast(
            String variant, View header, TextView name, TextView detail) {
        Bitmap background = Bitmap.createBitmap(1, header.getHeight(), Bitmap.Config.ARGB_8888);
        background.eraseColor(Color.WHITE);
        Drawable gradient = header.getBackground();
        Rect bounds = gradient.copyBounds();
        try {
            gradient.setBounds(0, 0, background.getWidth(), background.getHeight());
            gradient.draw(new Canvas(background));
            for (TextView label : new TextView[] {name, detail}) {
                double contrast = ColorUtils.calculateContrast(label.getCurrentTextColor(),
                        background.getPixel(0, label.getBottom() - 1));
                assertTrue(variant + " " + label.getText() + " contrast on bright video: " + contrast,
                        contrast >= 4.5);
            }
            int lastPixel = background.getPixel(0, background.getHeight() - 1);
            assertTrue(variant + " gradient fades into the video with no visible bottom edge",
                    ColorUtils.calculateContrast(lastPixel, Color.WHITE) < 1.08);
        } finally {
            gradient.setBounds(bounds);
            background.recycle();
        }
    }
}

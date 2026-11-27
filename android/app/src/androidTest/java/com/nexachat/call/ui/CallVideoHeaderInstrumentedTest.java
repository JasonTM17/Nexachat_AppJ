package com.nexachat.call.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.nexachat.R;
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
        String variant = language + " " + theme + " " + width + "x" + height;
        assertEquals(variant + " timer is not announced every second",
                View.ACCESSIBILITY_LIVE_REGION_NONE, detail.getAccessibilityLiveRegion());

        for (String text : new String[] {"02:16", context.getString(R.string.call_state_reconnecting)}) {
            detail.setText(text);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            assertTrue(variant + " header stays above the center: " + header.getTop()
                            + ".." + header.getBottom(),
                    header.getTop() >= 0 && header.getBottom() <= height / 2);
            assertTrue(variant + " header leaves a gap before the camera preview",
                    preview.getLeft() - header.getRight() >= 12);
            if (text.equals("02:16")) {
                assertEquals(variant + " duration stays on one line", 1, detail.getLineCount());
            }
            assertTrue(variant + " " + text + " is visible in full",
                    detail.getLayout().getHeight() <= detail.getHeight());
            int lastLine = detail.getLineCount() - 1;
            assertEquals(variant + " " + text + " reaches the last character",
                    text.length(), detail.getLayout().getLineEnd(lastLine));
        }
    }
}

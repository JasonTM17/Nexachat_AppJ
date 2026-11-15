package com.nexachat.feature.chat;

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

/**
 * The persistent keyset notice in the chat layout: it exists in portrait and
 * landscape, starts hidden, carries the exact localized copy, and its action
 * button is wired for the fragment to open the encryption screen. Nothing
 * here renders a server SYSTEM message — the notice is local UI only.
 */
@RunWith(AndroidJUnit4.class)
public final class ChatKeysetNoticeInstrumentedTest {

    @Test
    public void keysetNoticeExistsHiddenInBothOrientationsAndThemes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int orientation : new int[] {
                    Configuration.ORIENTATION_PORTRAIT,
                    Configuration.ORIENTATION_LANDSCAPE}) {
                for (boolean night : new boolean[] {false, true}) {
                    Context context = themedContext(Locale.US, night, orientation);
                    View root = inflate(context, R.layout.fragment_chat);
                    View notice = root.findViewById(R.id.keysetNotice);
                    assertNotNull("keysetNotice must exist in fragment_chat", notice);
                    assertEquals(View.GONE, notice.getVisibility());
                    assertNotNull(notice.findViewById(R.id.keysetNoticeText));
                    View action = notice.findViewById(R.id.keysetNoticeAction);
                    assertNotNull(action);
                    assertTrue(action.isClickable());
                }
            }
        });
    }

    @Test
    public void keysetNoticeShowsTheChangedCopyAndCompareAction() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext(
                    Locale.US, false, Configuration.ORIENTATION_PORTRAIT);
            View root = inflate(context, R.layout.fragment_chat);
            View notice = root.findViewById(R.id.keysetNotice);
            TextView text = notice.findViewById(R.id.keysetNoticeText);
            text.setText(R.string.e2ee_keyset_changed_notice);
            notice.setVisibility(View.VISIBLE);

            assertEquals(
                    "Security code changed. Sending is paused until you compare again.",
                    text.getText().toString());
            assertEquals("Compare",
                    context.getString(R.string.e2ee_keyset_notice_action));
        });
    }

    @Test
    public void keysetNoticeCopyIsLocalizedInVietnamese() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext(Locale.forLanguageTag("vi-VN"),
                    false, Configuration.ORIENTATION_PORTRAIT);
            assertEquals(
                    "Mã bảo mật đã thay đổi. Đang tạm dừng gửi cho đến khi bạn đối chiếu lại.",
                    context.getString(R.string.e2ee_keyset_changed_notice));
            assertEquals("Đối chiếu",
                    context.getString(R.string.e2ee_keyset_notice_action));
        });
    }

    @Test
    public void keysetNoticeLaysOutAtDoubleFontScaleInVietnamese() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext(Locale.forLanguageTag("vi-VN"),
                    false, Configuration.ORIENTATION_PORTRAIT);
            Configuration config = context.getResources().getConfiguration();
            config.fontScale = 2.0f;
            Context large = new ContextThemeWrapper(
                    context.createConfigurationContext(config),
                    R.style.Theme_NexaChat_Views);
            View notice = LayoutInflater.from(large)
                    .inflate(R.layout.include_chat_keyset_notice,
                            new FrameLayout(large), false);
            ((TextView) notice.findViewById(R.id.keysetNoticeText))
                    .setText(R.string.e2ee_keyset_changed_notice);
            ((TextView) notice.findViewById(R.id.keysetNoticeAction))
                    .setText(R.string.e2ee_keyset_notice_action);
            notice.setVisibility(View.VISIBLE);

            int width = View.MeasureSpec.makeMeasureSpec(
                    360 * large.getResources().getDisplayMetrics().densityDpi
                            / DisplayMetrics.DENSITY_DEFAULT,
                    View.MeasureSpec.EXACTLY);
            notice.measure(width, View.MeasureSpec.UNSPECIFIED);
            notice.layout(0, 0, notice.getMeasuredWidth(), notice.getMeasuredHeight());

            assertTrue("notice must lay out at fontScale 2.0",
                    notice.getMeasuredHeight() > 0);
        });
    }

    private static Context themedContext(Locale locale, boolean night, int orientation) {
        Context app = ApplicationProvider.getApplicationContext();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));
        config.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        config.fontScale = 1.0f;
        config.orientation = orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            config.screenWidthDp = Math.max(config.screenWidthDp, 800);
        }
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return new ContextThemeWrapper(
                app.createConfigurationContext(config), R.style.Theme_NexaChat_Views);
    }

    private static View inflate(Context context, int layout) {
        return LayoutInflater.from(context).inflate(layout, new FrameLayout(context), false);
    }
}

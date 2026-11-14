package com.nexachat.feature.chat.adapter;

import static org.junit.Assert.assertEquals;
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
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.nexachat.R;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ChatAndInboxLayoutInstrumentedTest {
    @Test
    public void rowDividerClearsTallContentInBothLanguagesAndThemes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Locale locale : new Locale[] {Locale.US, Locale.forLanguageTag("vi-VN")}) {
                for (boolean night : new boolean[] {false, true}) {
                    Context context = themedContext(locale, night, 2f);
                    ConstraintLayout row = (ConstraintLayout) inflate(context, R.layout.item_inbox_conversation);
                    TextView preview = row.findViewById(R.id.preview);
                    preview.setText("NexaChat");
                    ChipGroup labels = row.findViewById(R.id.labelChips);
                    for (int number = 1; number <= 5; number++) {
                        Chip chip = new Chip(context);
                        chip.setText(context.getString(R.string.settings_language) + " " + number);
                        labels.addView(chip);
                    }
                    labels.setVisibility(View.VISIBLE);
                    layout(row, 320);
                    View divider = row.getChildAt(row.getChildCount() - 1);
                    int bottomGap = context.getResources().getDimensionPixelSize(R.dimen.space_sm);
                    assertTrue("Fixture must wrap labels",
                            labels.getChildAt(0).getTop() < labels.getChildAt(labels.getChildCount() - 1).getTop());
                    assertTrue("Divider overlaps labels or lacks the required gap for " + locale + "/" + night,
                            divider.getTop() >= labels.getBottom() + bottomGap);
                    assertEquals("Divider must sit at the row edge", row.getHeight(), divider.getBottom());
                }
            }
        });
    }

    @Test
    public void unmeasuredChatRowsRetainTheirXmlWidthLimits() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext(Locale.US, false, 1f);
            View row = inflate(context, R.layout.item_message_text_outgoing);
            float initialStart = guidePercent(row, R.id.bubbleStartLimit);
            float initialEnd = guidePercent(row, R.id.bubbleEndLimit);
            new MessageBubbleLayoutBinder().bind(row);
            assertEquals(initialStart, guidePercent(row, R.id.bubbleStartLimit), 0f);
            assertEquals(initialEnd, guidePercent(row, R.id.bubbleEndLimit), 0f);
        });
    }

    private static Context themedContext(Locale locale, boolean night, float fontScale) {
        Context app = ApplicationProvider.getApplicationContext();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));
        config.fontScale = fontScale;
        config.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return new ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_NexaChat_Views);
    }

    private static View inflate(Context context, int layout) {
        return LayoutInflater.from(context).inflate(layout, new FrameLayout(context), false);
    }

    private static void layout(View view, int widthPx) {
        view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, widthPx, view.getMeasuredHeight());
    }

    private static float guidePercent(View row, int id) {
        return ((ConstraintLayout.LayoutParams) row.findViewById(id).getLayoutParams()).guidePercent;
    }
}

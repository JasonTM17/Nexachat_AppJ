package com.nexachat.feature.summary;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.nexachat.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Measures the summary action footer at a narrow width and large type scale.
 */
@RunWith(AndroidJUnit4.class)
public final class SummaryFooterMeasureInstrumentedTest {

  @Test
  public void narrowSheetDesiredFooterExceedsRowAtLargeType() {
    Context app = ApplicationProvider.getApplicationContext();
    Configuration config = new Configuration(app.getResources().getConfiguration());
    config.fontScale = 1.3f;
    config.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
    Context themed = app.createConfigurationContext(config);
    float widthDp = 320f;
    int widthPx = Math.round(widthDp * themed.getResources().getDisplayMetrics().density);

    FrameLayout host = new FrameLayout(themed);
    View root = LayoutInflater.from(themed).inflate(R.layout.bottom_sheet_summary, host, false);
    int widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY);
    int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
    root.measure(widthSpec, heightSpec);
    root.layout(0, 0, widthPx, root.getMeasuredHeight());

    LinearLayout actions = root.findViewById(R.id.summaryActions);
    int content =
            actions.getWidth()
                    - actions.getPaddingLeft()
                    - actions.getPaddingRight();
    int desired = SummaryBottomSheetFragment.SummaryFooterRules.desiredHorizontalSpan(actions);
    assertTrue(content > 0);
    assertTrue(SummaryBottomSheetFragment.SummaryFooterRules.stack(desired, content));
  }
}

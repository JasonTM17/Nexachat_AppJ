package com.nexachat.feature.chat.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.material.color.MaterialColors;
import com.nexachat.R;

/** A seekable, RTL-aware waveform with a minimum 48dp interaction target. */
public final class WaveformView extends View {

    public interface OnSeekListener {
        void onSeek(float fraction);
    }

    public static final class BarGeometry {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final boolean played;

        BarGeometry(float left, float top, float right, float bottom, boolean played) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.played = played;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int touchSlop;
    private byte[] bars = WaveformExtractor.flatBars();
    private float progress;
    private float downX;
    private float pendingSeekFraction;
    private boolean dragging;
    private int playedColor;
    private int unplayedColor;
    @Nullable private OnSeekListener seekListener;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        playedColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary,
                ContextCompat.getColor(context, R.color.brand_indigo));
        unplayedColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(context, R.color.colorOnSurfaceVariant));
        setClickable(true);
        setFocusable(true);
        setMinimumHeight(dp(48));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setBars(@Nullable byte[] values) {
        bars = values == null ? new byte[0] : values.clone();
        invalidate();
        invalidateAccessibilityState();
    }

    public void setProgress(float value) {
        progress = clamp(value);
        invalidate();
        invalidateAccessibilityState();
    }

    public float getProgressFraction() {
        return progress;
    }

    public void setColors(int played, int unplayed) {
        playedColor = played;
        unplayedColor = unplayed;
        invalidate();
    }

    public void setOnSeekListener(@Nullable OnSeekListener listener) {
        seekListener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int minWidth = Math.max(getSuggestedMinimumWidth(), dp(120));
        int minHeight = Math.max(getSuggestedMinimumHeight(), dp(48));
        setMeasuredDimension(
                resolveSize(minWidth, widthMeasureSpec),
                resolveSize(minHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        BarGeometry[] geometry = calculateBarGeometry(
                getWidth(), getHeight(), bars, dp(2), dp(3),
                getLayoutDirection() == View.LAYOUT_DIRECTION_RTL, progress);
        float radius = dp(2);
        for (BarGeometry bar : geometry) {
            paint.setColor(bar.played ? playedColor : unplayedColor);
            rect.set(bar.left, bar.top, bar.right, bar.bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                pendingSeekFraction = fractionAt(downX);
                dragging = false;
                setPressed(true);
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop) {
                    dragging = true;
                }
                if (dragging) {
                    pendingSeekFraction = fractionAt(event.getX());
                    setProgress(pendingSeekFraction);
                }
                return true;
            case MotionEvent.ACTION_UP:
                pendingSeekFraction = fractionAt(event.getX());
                setPressed(false);
                if (dragging) {
                    setProgress(pendingSeekFraction);
                    if (seekListener != null) {
                        seekListener.onSeek(pendingSeekFraction);
                    }
                } else {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (seekListener != null) {
            seekListener.onSeek(pendingSeekFraction);
        }
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(@NonNull AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, progress));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle arguments) {
        if (android.os.Build.VERSION.SDK_INT >= 24
                && action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                && arguments != null
                && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
            float value = clamp(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE));
            setProgress(value);
            if (seekListener != null) {
                seekListener.onSeek(value);
            }
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @NonNull
    public static BarGeometry[] calculateBarGeometry(
            float width,
            float height,
            @Nullable byte[] amplitudes,
            float gap,
            float minimumHeight,
            boolean rtl,
            float progress) {
        if (width <= 0f || height <= 0f) {
            return new BarGeometry[0];
        }
        int count = amplitudes == null || amplitudes.length == 0 ? 1 : amplitudes.length;
        float safeGap = Math.min(Math.max(0f, gap), width / Math.max(1f, count * 4f));
        float barWidth = Math.max(0f, (width - safeGap * (count - 1)) / count);
        float safeProgress = clamp(progress);
        BarGeometry[] result = new BarGeometry[count];
        for (int index = 0; index < count; index++) {
            int amplitude = amplitudes == null || amplitudes.length == 0
                    ? 8
                    : amplitudes[index] & 0xff;
            float barHeight = Math.min(height, Math.max(minimumHeight,
                    height * amplitude / 255f));
            float left = index * (barWidth + safeGap);
            int logicalIndex = rtl ? count - 1 - index : index;
            boolean played = (logicalIndex + 0.5f) / count <= safeProgress;
            float top = (height - barHeight) / 2f;
            result[index] = new BarGeometry(left, top, left + barWidth, top + barHeight, played);
        }
        return result;
    }

    private float fractionAt(float x) {
        float fraction = width() == 0 ? 0f : clamp(x / width());
        return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1f - fraction : fraction;
    }

    private int width() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void invalidateAccessibilityState() {
        invalidate();
    }

    private static float clamp(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }
}

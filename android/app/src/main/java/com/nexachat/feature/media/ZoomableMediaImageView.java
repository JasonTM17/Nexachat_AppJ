package com.nexachat.feature.media;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.nexachat.R;

/** Image-only pinch/pan/double-tap and dismiss gestures, with accessible zoom actions. */
public final class ZoomableMediaImageView extends AppCompatImageView {
    private final Matrix imageTransform = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float zoom = 1f, panX, panY, lastX, lastY, downX, downY;
    private boolean multiplePointers;
    @Nullable private Runnable onDismiss;

    public ZoomableMediaImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        setClickable(true);
        setContentDescription(context.getString(R.string.cd_media_photo_zoom));
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomTo(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { return performClick(); }
            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomTo(zoom > 1f ? 1f : 2f, e.getX(), e.getY());
                return true;
            }
        });
        ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                context.getString(R.string.media_toggle_zoom), (v, args) -> { performClick(); return true; });
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.media_zoom_in), (v, args) -> {
            zoomTo(zoom + 1f, getWidth() / 2f, getHeight() / 2f); return true;
        });
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.media_zoom_out), (v, args) -> {
            zoomTo(zoom - 1f, getWidth() / 2f, getHeight() / 2f); return true;
        });
    }

    public void setOnDismiss(@Nullable Runnable action) { onDismiss = action; }

    @Override public boolean performClick() {
        super.performClick();
        zoomTo(zoom > 1f ? 1f : 2f, getWidth() / 2f, getHeight() / 2f);
        return true;
    }

    @Override public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        zoom = 1f; panX = panY = 0;
        if (imageTransform != null) updateTransform();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        zoom = 1f; panX = panY = 0;
        updateTransform();
    }

    private void zoomTo(float value, float focusX, float focusY) {
        float next = MediaZoomRules.clampZoom(value), factor = next / zoom;
        panX = (panX + getWidth() / 2f - focusX) * factor + focusX - getWidth() / 2f;
        panY = (panY + getHeight() / 2f - focusY) * factor + focusY - getHeight() / 2f;
        zoom = next;
        updateTransform();
        ViewCompat.setStateDescription(this, getContext().getString(R.string.media_zoom_level, Math.round(zoom * 100)));
    }

    private void updateTransform() {
        Drawable drawable = getDrawable();
        if (drawable == null || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0
                || getWidth() == 0 || getHeight() == 0) return;
        float fit = Math.min((float) getWidth() / drawable.getIntrinsicWidth(),
                (float) getHeight() / drawable.getIntrinsicHeight());
        float width = drawable.getIntrinsicWidth() * fit * zoom;
        float height = drawable.getIntrinsicHeight() * fit * zoom;
        panX = MediaZoomRules.clampPan(panX, width, getWidth());
        panY = MediaZoomRules.clampPan(panY, height, getHeight());
        imageTransform.setScale(fit * zoom, fit * zoom);
        imageTransform.postTranslate((getWidth() - width) / 2 + panX, (getHeight() - height) / 2 + panY);
        setImageMatrix(imageTransform);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = lastX = event.getX(); downY = lastY = event.getY(); multiplePointers = false;
            }
            case MotionEvent.ACTION_POINTER_DOWN -> multiplePointers = true;
            case MotionEvent.ACTION_MOVE -> {
                if (zoom > 1f && !scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    panX += event.getX() - lastX; panY += event.getY() - lastY; updateTransform();
                }
                lastX = event.getX(); lastY = event.getY();
            }
            case MotionEvent.ACTION_POINTER_UP -> {
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                lastX = event.getX(remaining); lastY = event.getY(remaining);
            }
            case MotionEvent.ACTION_UP -> {
                float threshold = getResources().getDimension(R.dimen.media_dismiss_distance);
                if (!multiplePointers && MediaZoomRules.shouldDismiss(zoom,
                        event.getX() - downX, event.getY() - downY, threshold) && onDismiss != null) onDismiss.run();
            }
        }
        return true;
    }
}

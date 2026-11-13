package com.nexachat.core.ui.components;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.bumptech.glide.Glide;
import com.nexachat.R;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.ui.AvatarInitialsSizing;
import com.nexachat.core.ui.MessageUiRules;

/**
 * Circular Android View avatar with an initials fallback, Glide image loading, and presence status dot.
 */
public class AvatarView extends FrameLayout {

    private final TextView initialsView;
    private final ImageView imageView;
    private final View statusDotView;
    private String displayName = "";
    @Nullable private Boolean onlineStatus = null;
    private boolean showStoryRing = false;

    public AvatarView(Context context) {
        this(context, null);
    }

    public AvatarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        setBackground(background);

        initialsView = new TextView(context);
        initialsView.setGravity(Gravity.CENTER);
        initialsView.setMaxLines(1);
        initialsView.setEllipsize(TextUtils.TruncateAt.END);
        initialsView.setTypeface(Typeface.DEFAULT_BOLD);
        initialsView.setIncludeFontPadding(false);
        addView(initialsView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setVisibility(GONE);
        addView(imageView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        statusDotView = new View(context);
        statusDotView.setVisibility(GONE);
        int dotPx = dpToPx(10);
        LayoutParams dotLp = new LayoutParams(dotPx, dotPx, Gravity.BOTTOM | Gravity.END);
        addView(statusDotView, dotLp);
    }

    public void setName(String name) {
        displayName = name == null ? "" : name;
        setContentDescription(displayName);
        applyColors();
        initialsView.setText(MessageUiRules.extractInitials(displayName));
        if (getWidth() > 0 && getHeight() > 0) {
            onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        }
    }

    public void setAvatarUrl(@Nullable String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            imageView.setVisibility(GONE);
            initialsView.setVisibility(VISIBLE);
            return;
        }
        initialsView.setVisibility(VISIBLE);
        imageView.setVisibility(VISIBLE);
        var request = Glide.with(this).load(AppConfig.absoluteMediaUrl(avatarUrl));
        if (AvatarPhotoLoadingRules.useCircleCropForPhotoUrl(avatarUrl)) {
            request = request.circleCrop();
        } else {
            request = request.centerCrop();
        }
        request.into(imageView);
    }

    public void setOnlineStatus(@Nullable Boolean online) {
        this.onlineStatus = online;
        updateStatusDot();
    }

    public void setShowStoryRing(boolean showRing) {
        this.showStoryRing = showRing;
        applyColors();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int diameterPx = Math.min(w, h);
        float density = getResources().getDisplayMetrics().density;
        float textPx = AvatarInitialsSizing.textSizePx(diameterPx, displayName, density);
        initialsView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);

        int dotPx = Math.max(dpToPx(10), Math.round(Math.min(w, h) * 0.26f));
        LayoutParams lp = (LayoutParams) statusDotView.getLayoutParams();
        if (lp != null && (lp.width != dotPx || lp.height != dotPx)) {
            lp.width = dotPx;
            lp.height = dotPx;
            statusDotView.setLayoutParams(lp);
        }
    }

    private void updateStatusDot() {
        if (onlineStatus == null) {
            statusDotView.setVisibility(GONE);
            return;
        }
        statusDotView.setVisibility(VISIBLE);
        int fillColor = Boolean.TRUE.equals(onlineStatus)
                ? ContextCompat.getColor(getContext(), R.color.stitch_online_dot)
                : resolveOnSurfaceVariantColor();
        int strokeColor = resolveSurfaceColor();
        int strokeWidth = dpToPx(2);

        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(fillColor);
        dotDrawable.setStroke(strokeWidth, strokeColor);
        statusDotView.setBackground(dotDrawable);
    }

    @ColorInt
    private int resolveOnSurfaceVariantColor() {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurfaceVariant, value, true)) {
            if (value.resourceId != 0) {
                return ContextCompat.getColor(getContext(), value.resourceId);
            }
            return value.data;
        }
        return ContextCompat.getColor(getContext(), R.color.colorOnSurfaceVariant);
    }

    @ColorInt
    private int resolveSurfaceColor() {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)) {
            if (value.resourceId != 0) {
                return ContextCompat.getColor(getContext(), value.resourceId);
            }
            return value.data;
        }
        return ContextCompat.getColor(getContext(), R.color.colorSurface);
    }

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
    }

    private void applyColors() {
        int container = AvatarColors.containerColor(getContext(), displayName);
        int onContainer = AvatarColors.onContainerColor(getContext(), displayName);
        GradientDrawable bg = (GradientDrawable) getBackground();
        if (bg != null) {
            bg.setColor(container);
            if (showStoryRing) {
                int strokeWidth = dpToPx(2);
                int strokeColor = ContextCompat.getColor(getContext(), R.color.brand_indigo);
                bg.setStroke(strokeWidth, strokeColor);
            } else {
                bg.setStroke(0, 0);
            }
        }
        initialsView.setTextColor(onContainer);
    }
}

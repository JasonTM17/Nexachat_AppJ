package com.nexachat.core.ui.components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.nexachat.R;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.ui.MessageUiRules;

/**
 * Circular avatar with initials fallback and Glide image loading (View counterpart to Compose {@code Avatar}).
 */
public class AvatarView extends FrameLayout {

    private final TextView initialsView;
    private final ImageView imageView;
    private String displayName = "";

    public AvatarView(Context context) {
        this(context, null);
    }

    public AvatarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float radius = 999f;
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        setBackground(background);

        initialsView = new TextView(context);
        initialsView.setGravity(Gravity.CENTER);
        initialsView.setMaxLines(1);
        initialsView.setEllipsize(TextUtils.TruncateAt.END);
        addView(initialsView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setVisibility(GONE);
        addView(imageView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setName(String name) {
        displayName = name == null ? "" : name;
        setContentDescription(displayName);
        applyColors();
        initialsView.setText(MessageUiRules.extractInitials(displayName));
    }

    public void setAvatarUrl(@Nullable String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            imageView.setVisibility(GONE);
            initialsView.setVisibility(VISIBLE);
            return;
        }
        initialsView.setVisibility(VISIBLE);
        imageView.setVisibility(VISIBLE);
        imageView.setVisibility(VISIBLE);
        initialsView.setVisibility(VISIBLE);
        Glide.with(this)
                .load(AppConfig.absoluteMediaUrl(avatarUrl))
                .centerCrop()
                .into(imageView);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float sizeDp = h / getResources().getDisplayMetrics().density;
        float sp = sizeDp >= 72 ? 26 : sizeDp >= 56 ? 20 : sizeDp >= 40 ? 15 : sizeDp >= 32 ? 12 : 10;
        initialsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    private void applyColors() {
        int container = AvatarColors.containerColor(getContext(), displayName);
        int onContainer = AvatarColors.onContainerColor(getContext(), displayName);
        GradientDrawable bg = (GradientDrawable) getBackground();
        bg.setColor(container);
        initialsView.setTextColor(onContainer);
    }
}

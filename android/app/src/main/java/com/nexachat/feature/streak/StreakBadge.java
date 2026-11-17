package com.nexachat.feature.streak;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import com.nexachat.R;
import java.text.NumberFormat;

/**
 * Small flame and number pill. It is hidden below a two-day streak. An at-risk streak keeps the
 * pill but drops to an outline with a dimmed flame, so it reads as fading rather than alarming.
 */
public final class StreakBadge extends AppCompatTextView {

    private static final int AT_RISK_FLAME_ALPHA = 140;

    public StreakBadge(Context context) {
        this(context, null);
    }

    public StreakBadge(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setGravity(Gravity.CENTER_VERTICAL);
        setSingleLine(true);
        setIncludeFontPadding(false);
        setCompoundDrawablePadding(getResources().getDimensionPixelSize(R.dimen.space_xxs));
        int horizontal = getResources().getDimensionPixelSize(R.dimen.space_sm);
        int vertical = getResources().getDimensionPixelSize(R.dimen.space_xxs);
        setPadding(horizontal, vertical, horizontal, vertical);
        setMinHeight(getResources().getDimensionPixelSize(R.dimen.streak_badge_min_height));
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_NexaChat_StreakBadge);
        setVisibility(GONE);
    }

    /** Shows the badge for {@code count} days, or hides it when the streak is too short. */
    public void setStreak(int count, boolean atRisk) {
        if (!StreakRules.isBadgeVisible(count)) {
            setVisibility(GONE);
            setContentDescription(null);
            return;
        }
        setText(NumberFormat.getIntegerInstance().format(count));
        setBackgroundResource(atRisk ? R.drawable.bg_streak_badge_at_risk : R.drawable.bg_streak_badge);
        setTextColor(ContextCompat.getColor(getContext(),
                atRisk ? R.color.streak_flame : R.color.streak_on_container));
        Drawable flame = ContextCompat.getDrawable(getContext(), R.drawable.ic_streak_flame);
        if (flame != null) {
            flame = flame.mutate();
            flame.setAlpha(atRisk ? AT_RISK_FLAME_ALPHA : 255);
            int size = getResources().getDimensionPixelSize(R.dimen.streak_badge_icon_size);
            flame.setBounds(0, 0, size, size);
            setCompoundDrawablesRelative(flame, null, null, null);
        }
        setContentDescription(getResources().getQuantityString(
                atRisk ? R.plurals.streak_badge_at_risk_cd : R.plurals.streak_badge_cd, count, count));
        setVisibility(VISIBLE);
    }
}

package com.nexachat.feature.chat;

import android.content.Context;
import android.content.res.Configuration;
import androidx.annotation.NonNull;
import com.nexachat.data.ChatColor;

/**
 * Resolves the outgoing-bubble surface from the selected {@link ChatColor} and
 * the live night mode. The bubble fill is the user's choice verbatim; the
 * embedded reply quote derives its container, accent and text tones from that
 * fill so the quote stays in the same color family while keeping WCAG
 * contrast — light schemes tint the block toward white, dark schemes lift it
 * slightly off the bubble.
 */
public final class ChatBubbleColors {

    private ChatBubbleColors() {}

    /** Bubble fill for the selected color in the given scheme. */
    public static int bubbleArgb(@NonNull ChatColor color, boolean night) {
        return color.bubbleArgb(night);
    }

    /** Fill of the reply-quote block inside an outgoing bubble. */
    public static int quoteContainerArgb(int bubbleArgb, boolean night) {
        return blend(bubbleArgb, 0xFFFFFFFF, night ? 0.08f : 0.85f);
    }

    /** Accent bar color of the reply-quote block inside an outgoing bubble. */
    public static int quoteAccentArgb(int bubbleArgb, boolean night) {
        return night
                ? blend(bubbleArgb, 0xFFFFFFFF, 0.68f)
                : blend(bubbleArgb, 0xFF000000, 0.10f);
    }

    /** Text color inside the reply-quote block on an outgoing bubble. */
    public static int quoteContentArgb(int bubbleArgb, boolean night) {
        return night
                ? blend(bubbleArgb, 0xFFFFFFFF, 0.92f)
                : blend(bubbleArgb, 0xFF000000, 0.30f);
    }

    /** Whether the context currently renders the dark scheme. */
    public static boolean isNight(@NonNull Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** ARGB lerp kept in raw int math so unit tests exercise the same code. */
    private static int blend(int first, int second, float towardsSecond) {
        float inverse = 1f - towardsSecond;
        int alpha = Math.round(((first >>> 24) & 0xFF) * inverse + ((second >>> 24) & 0xFF) * towardsSecond);
        int red = Math.round(((first >> 16) & 0xFF) * inverse + ((second >> 16) & 0xFF) * towardsSecond);
        int green = Math.round(((first >> 8) & 0xFF) * inverse + ((second >> 8) & 0xFF) * towardsSecond);
        int blue = Math.round((first & 0xFF) * inverse + (second & 0xFF) * towardsSecond);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
}

package com.nexachat.core.ui;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import com.nexachat.R;

/**
 * The one emoji set the app offers for reactions, shared by message reactions and feed post
 * reactions so both surfaces stay identical.
 */
public final class ReactionSet {

    private static final String[] EMOJI = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
    private static final int[] LABELS = {
            R.string.reaction_like,
            R.string.reaction_love,
            R.string.reaction_laugh,
            R.string.reaction_surprised,
            R.string.reaction_sad,
            R.string.reaction_thanks
    };

    private ReactionSet() { }

    public static int size() {
        return EMOJI.length;
    }

    @NonNull
    public static String emoji(int index) {
        return EMOJI[index];
    }

    @StringRes
    public static int label(int index) {
        return LABELS[index];
    }

    /** Index of {@code emoji} in the set, or -1 when the server sent one the app does not offer. */
    public static int indexOf(@NonNull String emoji) {
        for (int i = 0; i < EMOJI.length; i++) {
            if (EMOJI[i].equals(emoji)) {
                return i;
            }
        }
        return -1;
    }
}

package com.nexachat.feature.inbox;

import android.content.Context;
import androidx.annotation.NonNull;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.inbox.adapter.ConversationPreviewHelper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Zalo-style mute/archive actions for a conversation. Presets are computed at
 * tap time so an open dialog cannot submit a stale instant.
 */
public final class ConversationPreferenceMenu {

    public interface Listener {
        /** {@code until} is null for "until turned back on". */
        void onMute(@androidx.annotation.Nullable Instant until);

        void onUnmute();

        void onArchive(boolean archive);

        default void onTogglePin(boolean pin) {}
    }

    private static final int ACTION_UNMUTE = 1;
    private static final int ACTION_MUTE_ONE_HOUR = 2;
    private static final int ACTION_MUTE_FOUR_HOURS = 3;
    private static final int ACTION_MUTE_UNTIL_EIGHT_AM = 4;
    private static final int ACTION_MUTE_FOREVER = 5;
    private static final int ACTION_ARCHIVE = 6;
    private static final int ACTION_UNARCHIVE = 7;
    private static final int ACTION_PIN = 8;
    private static final int ACTION_UNPIN = 9;

    private ConversationPreferenceMenu() {}

    /** Inbox long-press: pin/unpin, mute presets (or unmute) plus archive/unarchive. */
    public static void showInboxActions(
            @NonNull Context context,
            @NonNull ConversationSummary conversation,
            @NonNull Listener listener) {
        boolean pinned = conversation.isPinned();
        List<CharSequence> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        labels.add(context.getString(pinned
                ? R.string.action_unpin_conversation
                : R.string.action_pin_conversation));
        actions.add(pinned ? ACTION_UNPIN : ACTION_PIN);

        boolean muted = conversation.isMutedAt(System.currentTimeMillis());
        addMuteItems(context, muted, labels, actions);
        boolean archived = conversation.isArchived();
        labels.add(context.getString(archived
                ? R.string.conversation_unarchive
                : R.string.conversation_archive));
        actions.add(archived ? ACTION_UNARCHIVE : ACTION_ARCHIVE);

        new MaterialAlertDialogBuilder(context)
                .setTitle(ConversationPreviewHelper.displayTitle(context, conversation))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) ->
                        dispatch(actions.get(which), listener))
                .show();
    }

    /** Chat overflow: only the mute presets, with one-tap unmute when muted. */
    public static void showMutePresets(
            @NonNull Context context,
            boolean muted,
            @NonNull Listener listener) {
        List<CharSequence> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        addMuteItems(context, muted, labels, actions);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mute_menu_title)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) ->
                        dispatch(actions.get(which), listener))
                .show();
    }

    private static void addMuteItems(
            Context context,
            boolean muted,
            List<CharSequence> labels,
            List<Integer> actions) {
        if (muted) {
            labels.add(context.getString(R.string.unmute));
            actions.add(ACTION_UNMUTE);
            return;
        }
        labels.add(context.getString(R.string.mute_for_one_hour));
        actions.add(ACTION_MUTE_ONE_HOUR);
        labels.add(context.getString(R.string.mute_for_four_hours));
        actions.add(ACTION_MUTE_FOUR_HOURS);
        labels.add(context.getString(R.string.mute_until_eight_am));
        actions.add(ACTION_MUTE_UNTIL_EIGHT_AM);
        labels.add(context.getString(R.string.mute_until_turned_on));
        actions.add(ACTION_MUTE_FOREVER);
    }

    private static void dispatch(int action, Listener listener) {
        Instant now = Instant.now();
        if (action == ACTION_UNMUTE) {
            listener.onUnmute();
        } else if (action == ACTION_MUTE_ONE_HOUR) {
            listener.onMute(MutePresets.oneHour(now));
        } else if (action == ACTION_MUTE_FOUR_HOURS) {
            listener.onMute(MutePresets.fourHours(now));
        } else if (action == ACTION_MUTE_UNTIL_EIGHT_AM) {
            listener.onMute(MutePresets.untilNextEightAm(now, ZoneId.systemDefault()));
        } else if (action == ACTION_MUTE_FOREVER) {
            listener.onMute(null);
        } else if (action == ACTION_ARCHIVE) {
            listener.onArchive(true);
        } else if (action == ACTION_UNARCHIVE) {
            listener.onArchive(false);
        } else if (action == ACTION_PIN) {
            listener.onTogglePin(true);
        } else if (action == ACTION_UNPIN) {
            listener.onTogglePin(false);
        }
    }
}

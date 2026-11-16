package com.nexachat.feature.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.core.ui.ForwardFilter;
import com.nexachat.core.ui.RecallWindowRules;
import com.nexachat.data.ChatMessage;
import com.nexachat.databinding.SheetMessageActionsBinding;
import com.nexachat.feature.ui.FormDialogs;
import com.nexachat.feature.ui.NexaBottomSheetFragment;
import java.util.ArrayList;
import java.util.List;

/** Long-press message action sheet; survives rotation through the child FragmentManager. */
public final class MessageActionSheetFragment extends NexaBottomSheetFragment {

    public static final String TAG = "message_actions_sheet";

    private static final String ARG_MESSAGE_ID = "messageId";
    private static final String ARG_CAN_PIN = "canPin";
    private static final String ARG_PINNED = "pinned";
    private static final String ARG_STARRED = "starred";
    private static final String ARG_DELETE_ACTION = "deleteAction";

    private static final String[] REACTIONS = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
    private static final int[] REACTION_NAMES = {R.string.reaction_like, R.string.reaction_love,
            R.string.reaction_laugh, R.string.reaction_surprised, R.string.reaction_sad,
            R.string.reaction_thanks};

    /** Resolves the long-pressed message after a recreation. */
    public interface Host {
        @Nullable ChatMessage findActionMessage(@NonNull String messageId);
    }

    public interface Actions {
        void reply(@NonNull ChatMessage message);
        void edit(@NonNull ChatMessage message);
        void delete(@NonNull ChatMessage message, @NonNull RecallWindowRules.DeleteAction action);
        void react(@NonNull ChatMessage message, @NonNull String emoji);
        void pin(@NonNull ChatMessage message);
        void star(@NonNull ChatMessage message);
        void report(@NonNull ChatMessage message);
        void forward(@NonNull ChatMessage message);
    }

    private enum MessageActionKind {
        REPLY, COPY, FORWARD, PIN, STAR, EDIT, REPORT, DELETE
    }

    private static final class MessageActionEntry {
        final String label;
        final String caption;
        final Runnable callback;
        final MessageActionKind kind;

        MessageActionEntry(String label, String caption, Runnable callback, MessageActionKind kind) {
            this.label = label;
            this.caption = caption;
            this.callback = callback;
            this.kind = kind;
        }
    }

    private SheetMessageActionsBinding binding;

    @NonNull
    public static MessageActionSheetFragment newInstance(
            @NonNull String messageId,
            boolean canPin,
            boolean pinned,
            @NonNull RecallWindowRules.DeleteAction deleteAction) {
        return newInstance(messageId, canPin, pinned, false, deleteAction);
    }

    @NonNull
    public static MessageActionSheetFragment newInstance(
            @NonNull String messageId,
            boolean canPin,
            boolean pinned,
            boolean starred,
            @NonNull RecallWindowRules.DeleteAction deleteAction) {
        MessageActionSheetFragment sheet = new MessageActionSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_MESSAGE_ID, messageId);
        args.putBoolean(ARG_CAN_PIN, canPin);
        args.putBoolean(ARG_PINNED, pinned);
        args.putBoolean(ARG_STARRED, starred);
        args.putString(ARG_DELETE_ACTION, deleteAction.name());
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    private Actions actions() {
        Fragment parent = getParentFragment();
        return parent instanceof Actions ? (Actions) parent : null;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SheetMessageActionsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments() == null ? Bundle.EMPTY : getArguments();
        String messageId = args.getString(ARG_MESSAGE_ID, "");
        ChatMessage message = null;
        Fragment parent = getParentFragment();
        if (parent instanceof Host host) {
            message = host.findActionMessage(messageId);
        }
        Actions callbacks = actions();
        if (message == null || callbacks == null) {
            dismissAllowingStateLoss();
            return;
        }
        bindSheet(args, message, callbacks);
    }

    private void bindSheet(
            @NonNull Bundle args,
            @NonNull ChatMessage message,
            @NonNull Actions callbacks) {
        Context context = requireContext();
        boolean canPin = args.getBoolean(ARG_CAN_PIN);
        boolean pinned = args.getBoolean(ARG_PINNED);
        RecallWindowRules.DeleteAction deleteAction = RecallWindowRules.DeleteAction.valueOf(
                args.getString(ARG_DELETE_ACTION, RecallWindowRules.DeleteAction.NONE.name()));

        List<MessageActionEntry> entries = new ArrayList<>();
        // Undecryptable content cannot be replied to, copied, or forwarded;
        // ciphertext content cannot be edited or forwarded (the server rejects
        // both, and forwarding decrypted plaintext would break e2ee).
        boolean undecryptable = message.isDecryptFailed();
        boolean sent = ChatBubbleRules.isMessageReplyable(message.getStatus())
                && !message.isDeleted() && !undecryptable;
        if (sent) {
            entries.add(new MessageActionEntry(getString(R.string.action_reply),
                    getString(R.string.action_reply_desc),
                    () -> callbacks.reply(message), MessageActionKind.REPLY));
        }
        if (message.getContent() != null && !message.getContent().isBlank()
                && !message.isDeleted() && !undecryptable) {
            entries.add(new MessageActionEntry(getString(R.string.action_copy),
                    getString(R.string.action_copy_desc),
                    () -> copy(context, message.getContent()), MessageActionKind.COPY));
        }
        if (sent && message.getContent() != null && !message.getContent().isBlank()
                && !message.isEncrypted()
                && ForwardFilter.canForward(message.getType(), message.isDeleted())) {
            entries.add(new MessageActionEntry(getString(R.string.action_forward),
                    getString(R.string.action_forward_desc),
                    () -> callbacks.forward(message), MessageActionKind.FORWARD));
        }
        if (canPin && sent) {
            entries.add(new MessageActionEntry(getString(pinned ? R.string.action_unpin : R.string.action_pin),
                    getString(pinned ? R.string.action_unpin_desc : R.string.action_pin_desc),
                    () -> callbacks.pin(message), MessageActionKind.PIN));
        }
        if (sent && !message.isDeleted()) {
            boolean starred = args.getBoolean(ARG_STARRED);
            entries.add(new MessageActionEntry(
                    getString(starred ? R.string.action_unstar : R.string.action_star),
                    getString(starred ? R.string.action_unstar_desc : R.string.action_star_desc),
                    () -> callbacks.star(message), MessageActionKind.STAR));
        }
        if (sent) {
            entries.add(new MessageActionEntry(getString(R.string.action_report),
                    getString(R.string.action_report_desc),
                    () -> callbacks.report(message), MessageActionKind.REPORT));
        }
        if (message.getMine() && sent && !message.isEncrypted()
                && "TEXT".equals(message.getType())) {
            entries.add(new MessageActionEntry(getString(R.string.action_edit),
                    getString(R.string.action_edit_desc),
                    () -> callbacks.edit(message), MessageActionKind.EDIT));
        }
        if (deleteAction != RecallWindowRules.DeleteAction.NONE) {
            int actionLabel;
            int actionCaption;
            switch (deleteAction) {
                case RECALL -> {
                    actionLabel = R.string.action_recall;
                    actionCaption = R.string.action_recall_desc;
                }
                case DELETE_FOR_ME -> {
                    actionLabel = R.string.action_delete_for_me;
                    actionCaption = R.string.action_delete_for_me_desc;
                }
                case ADMIN_DELETE -> {
                    actionLabel = R.string.action_admin_delete;
                    actionCaption = R.string.action_admin_delete_desc;
                }
                default -> throw new IllegalStateException("Unknown delete action");
            }
            entries.add(new MessageActionEntry(getString(actionLabel),
                    getString(actionCaption),
                    () -> confirmDelete(message, deleteAction), MessageActionKind.DELETE));
        }

        ViewCompat.setAccessibilityHeading(binding.messageActionsTitle, true);
        if (sent) {
            populateReactionButtons(binding.reactionRow, context, emoji -> {
                dismiss();
                callbacks.react(message, emoji);
            });
        } else {
            binding.reactionScroller.setVisibility(View.GONE);
        }
        LayoutInflater inflater = LayoutInflater.from(context);
        for (MessageActionEntry entry : entries) {
            if (entry.kind == MessageActionKind.DELETE) {
                addDivider(binding.messageActionList);
            }
            View row = inflater.inflate(R.layout.item_message_action_row, binding.messageActionList, false);
            bindMessageActionRow(row, entry.label, entry.caption, entry.kind);
            row.setOnClickListener(v -> {
                dismiss();
                entry.callback.run();
            });
            binding.messageActionList.addView(row);
        }
    }

    /** Inset separator ahead of the destructive row, matching the action-sheet design. */
    private static void addDivider(@NonNull LinearLayout actionList) {
        Context context = actionList.getContext();
        View divider = new View(context);
        divider.setBackgroundColor(MaterialColors.getColor(
                actionList, com.google.android.material.R.attr.colorSurfaceContainerHighest));
        int height = context.getResources().getDimensionPixelSize(R.dimen.divider_height);
        int inset = context.getResources().getDimensionPixelSize(R.dimen.space_md);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        params.setMargins(inset, 0, inset, 0);
        actionList.addView(divider, params);
    }

    private static void bindMessageActionRow(
            @NonNull View row,
            @NonNull CharSequence label,
            @NonNull CharSequence caption,
            @NonNull MessageActionKind kind) {
        TextView actionLabel = row.findViewById(R.id.actionLabel);
        TextView actionCaption = row.findViewById(R.id.actionCaption);
        ImageView actionIcon = row.findViewById(R.id.actionIcon);
        FrameLayout actionIconTile = row.findViewById(R.id.actionIconTile);
        actionLabel.setText(label);
        actionCaption.setText(caption);
        int iconRes;
        int tileBg = R.drawable.bg_settings_tile_tint;
        int iconAttr = com.google.android.material.R.attr.colorOnSurface;
        int labelAttr = com.google.android.material.R.attr.colorOnSurface;
        int captionAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
        switch (kind) {
            case REPLY -> {
                iconRes = R.drawable.ic_reply;
                iconAttr = com.google.android.material.R.attr.colorPrimary;
            }
            case COPY -> {
                iconRes = R.drawable.ic_content_copy;
                iconAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
            }
            case FORWARD -> iconRes = R.drawable.ic_forward;
            case PIN -> iconRes = R.drawable.ic_push_pin;
            case STAR -> iconRes = R.drawable.ic_star_outline;
            case REPORT -> {
                iconRes = R.drawable.ic_flag;
                iconAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
            }
            case EDIT -> iconRes = R.drawable.ic_edit;
            case DELETE -> {
                iconRes = R.drawable.ic_delete;
                tileBg = R.drawable.bg_settings_tile_error;
                iconAttr = com.google.android.material.R.attr.colorError;
                labelAttr = com.google.android.material.R.attr.colorError;
                captionAttr = com.google.android.material.R.attr.colorError;
            }
            default -> throw new IllegalStateException("Unknown message action");
        }
        actionIcon.setImageResource(iconRes);
        actionIconTile.setBackgroundResource(tileBg);
        ImageViewCompat.setImageTintList(actionIcon, ColorStateList.valueOf(
                MaterialColors.getColor(actionIcon, iconAttr)));
        actionLabel.setTextColor(MaterialColors.getColor(actionLabel, labelAttr));
        actionCaption.setTextColor(MaterialColors.getColor(actionCaption, captionAttr));
    }

    private static void populateReactionButtons(
            @NonNull LinearLayout row,
            @NonNull Context context,
            @NonNull java.util.function.Consumer<String> onPick) {
        row.removeAllViews();
        int target = context.getResources().getDimensionPixelSize(R.dimen.min_touch_target);
        int gap = context.getResources().getDimensionPixelSize(R.dimen.space_xs);
        for (int i = 0; i < REACTIONS.length; i++) {
            String emoji = REACTIONS[i];
            // Circular tiles: the ripple is masked to the tile's circle so it
            // never paints past the pill strip's rounded ends.
            TextView tile = new TextView(context);
            tile.setText(emoji);
            TextViewCompat.setTextAppearance(tile, R.style.TextAppearance_NexaChat_TitleLarge);
            tile.setContentDescription(context.getString(REACTION_NAMES[i]));
            tile.setGravity(Gravity.CENTER);
            tile.setMinWidth(target);
            tile.setMinHeight(target);
            tile.setBackgroundResource(R.drawable.bg_reaction_tile);
            tile.setFocusable(true);
            tile.setOnClickListener(v -> onPick.accept(emoji));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) {
                params.setMarginStart(gap);
            }
            row.addView(tile, params);
        }
    }

    private void confirmDelete(
            @NonNull ChatMessage message,
            @NonNull RecallWindowRules.DeleteAction action) {
        int title;
        int body;
        switch (action) {
            case RECALL -> {
                title = R.string.dialog_recall_message_title;
                body = R.string.dialog_recall_message_body;
            }
            case DELETE_FOR_ME -> {
                title = R.string.dialog_delete_for_me_title;
                body = R.string.dialog_delete_for_me_body;
            }
            case ADMIN_DELETE -> {
                title = R.string.dialog_admin_delete_title;
                body = R.string.dialog_admin_delete_body;
            }
            default -> throw new IllegalArgumentException("A delete action is required");
        }
        FormDialogs.markDestructive(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    Actions callbacks = actions();
                    if (callbacks != null) {
                        callbacks.delete(message, action);
                    }
                })
                .show());
    }

    private static void copy(@NonNull Context context, @NonNull String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("message", text));
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}

package com.nexachat.feature.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.ImageViewCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.core.ui.ForwardFilter;
import com.nexachat.core.ui.RecallWindowRules;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.chat.adapter.ForwardTargetAdapter;
import com.nexachat.databinding.DialogForwardBinding;
import java.util.ArrayList;
import java.util.List;

/** Native Android views for chat actions, the composer banners and media picker. */
public final class ChatInteractionsViews {
    private static final String[] REACTIONS = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
    private static final int[] REACTION_NAMES = {R.string.reaction_like, R.string.reaction_love,
            R.string.reaction_laugh, R.string.reaction_surprised, R.string.reaction_sad,
            R.string.reaction_thanks};

    private ChatInteractionsViews() {}

    public interface MessageActions {
        void reply(@NonNull ChatMessage message);
        void edit(@NonNull ChatMessage message);
        void delete(@NonNull ChatMessage message, @NonNull RecallWindowRules.DeleteAction action);
        void react(@NonNull ChatMessage message, @NonNull String emoji);
        void pin(@NonNull ChatMessage message);
        void forward(@NonNull ChatMessage message);
    }

    private enum MessageActionKind {
        REPLY, COPY, FORWARD, PIN, EDIT, DELETE
    }

    private static final class MessageActionEntry {
        final String label;
        final Runnable callback;
        final MessageActionKind kind;

        MessageActionEntry(String label, Runnable callback, MessageActionKind kind) {
            this.label = label;
            this.callback = callback;
            this.kind = kind;
        }
    }

    public static void showMessageActions(
            @NonNull Context context,
            @NonNull ChatMessage message,
            boolean canPin,
            boolean pinned,
            @NonNull RecallWindowRules.DeleteAction deleteAction,
            @NonNull MessageActions actions) {
        List<MessageActionEntry> entries = new ArrayList<>();
        boolean sent = ChatBubbleRules.isMessageReplyable(message.getStatus()) && !message.isDeleted();
        if (sent) {
            entries.add(new MessageActionEntry(context.getString(R.string.action_reply),
                    () -> actions.reply(message), MessageActionKind.REPLY));
        }
        if (message.getContent() != null && !message.getContent().isBlank() && !message.isDeleted()) {
            entries.add(new MessageActionEntry(context.getString(R.string.action_copy),
                    () -> copy(context, message.getContent()), MessageActionKind.COPY));
        }
        if (sent && message.getContent() != null && !message.getContent().isBlank()
                && ForwardFilter.canForward(message.getType(), message.isDeleted())) {
            entries.add(new MessageActionEntry(context.getString(R.string.action_forward),
                    () -> actions.forward(message), MessageActionKind.FORWARD));
        }
        if (canPin && sent) {
            entries.add(new MessageActionEntry(context.getString(pinned ? R.string.action_unpin : R.string.action_pin),
                    () -> actions.pin(message), MessageActionKind.PIN));
        }
        if (message.getMine() && sent && "TEXT".equals(message.getType())) {
            entries.add(new MessageActionEntry(context.getString(R.string.action_edit),
                    () -> actions.edit(message), MessageActionKind.EDIT));
        }
        if (deleteAction != RecallWindowRules.DeleteAction.NONE) {
            int actionLabel = switch (deleteAction) {
                case RECALL -> R.string.action_recall;
                case DELETE_FOR_ME -> R.string.action_delete_for_me;
                case ADMIN_DELETE -> R.string.action_admin_delete;
                case NONE -> throw new IllegalStateException("No delete action label");
                default -> throw new IllegalStateException("Unknown delete action");
            };
            entries.add(new MessageActionEntry(context.getString(actionLabel),
                    () -> confirmDelete(context, message, deleteAction, actions), MessageActionKind.DELETE));
        }
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View sheet = LayoutInflater.from(context).inflate(R.layout.sheet_message_actions, null, false);
        TextView title = sheet.findViewById(R.id.messageActionsTitle);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        View reactionScroller = sheet.findViewById(R.id.reactionScroller);
        LinearLayout reactionRow = sheet.findViewById(R.id.reactionRow);
        LinearLayout actionList = sheet.findViewById(R.id.messageActionList);
        if (sent) {
            populateReactionButtons(reactionRow, context, emoji -> {
                dialog.dismiss();
                actions.react(message, emoji);
            });
        } else {
            reactionScroller.setVisibility(View.GONE);
        }
        LayoutInflater inflater = LayoutInflater.from(context);
        for (MessageActionEntry entry : entries) {
            View row = inflater.inflate(R.layout.item_message_action_row, actionList, false);
            bindMessageActionRow(row, entry.label, entry.kind);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                entry.callback.run();
            });
            actionList.addView(row);
        }
        dialog.setContentView(sheet);
        dialog.show();
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    public static void showReactionPicker(@NonNull Context context, @NonNull java.util.function.Consumer<String> onPick) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        dialog.setContentView(reactionRow(context, emoji -> { dialog.dismiss(); onPick.accept(emoji); }));
        dialog.show();
    }

    private static void bindMessageActionRow(
            @NonNull View row,
            @NonNull CharSequence label,
            @NonNull MessageActionKind kind) {
        TextView actionLabel = row.findViewById(R.id.actionLabel);
        ImageView actionIcon = row.findViewById(R.id.actionIcon);
        FrameLayout actionIconTile = row.findViewById(R.id.actionIconTile);
        actionLabel.setText(label);
        int iconRes;
        int tileBg = R.drawable.bg_settings_tile_tint;
        int iconAttr = com.google.android.material.R.attr.colorOnSurface;
        int labelAttr = com.google.android.material.R.attr.colorOnSurface;
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
            case EDIT -> iconRes = R.drawable.ic_edit;
            case DELETE -> {
                iconRes = R.drawable.ic_delete;
                tileBg = R.drawable.bg_settings_tile_error;
                iconAttr = com.google.android.material.R.attr.colorError;
                labelAttr = com.google.android.material.R.attr.colorError;
            }
            default -> throw new IllegalStateException("Unknown message action");
        }
        actionIcon.setImageResource(iconRes);
        actionIconTile.setBackgroundResource(tileBg);
        ImageViewCompat.setImageTintList(actionIcon, ColorStateList.valueOf(
                MaterialColors.getColor(actionIcon, iconAttr)));
        actionLabel.setTextColor(MaterialColors.getColor(actionLabel, labelAttr));
    }

    private static HorizontalScrollView reactionRow(Context context,
            java.util.function.Consumer<String> onPick) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(MaterialColors.getColor(row,
                com.google.android.material.R.attr.colorSurfaceContainerLow));
        int pad = context.getResources().getDimensionPixelSize(R.dimen.space_sm);
        row.setPadding(pad, pad, pad, pad);
        populateReactionButtons(row, context, onPick);
        scroll.addView(row);
        return scroll;
    }

    private static void populateReactionButtons(
            @NonNull LinearLayout row,
            @NonNull Context context,
            @NonNull java.util.function.Consumer<String> onPick) {
        row.removeAllViews();
        for (int i = 0; i < REACTIONS.length; i++) {
            String emoji = REACTIONS[i];
            MaterialButton button = new MaterialButton(context, null,
                    com.google.android.material.R.attr.borderlessButtonStyle);
            button.setText(emoji);
            button.setTextAppearance(R.style.TextAppearance_NexaChat_TitleLarge);
            button.setContentDescription(context.getString(REACTION_NAMES[i]));
            int target = context.getResources().getDimensionPixelSize(R.dimen.min_touch_target);
            button.setMinWidth(target);
            button.setMinHeight(target);
            button.setOnClickListener(v -> onPick.accept(emoji));
            row.addView(button, new LinearLayout.LayoutParams(-2, -2));
        }
    }

    private static void confirmDelete(
            Context context,
            ChatMessage message,
            RecallWindowRules.DeleteAction action,
            MessageActions actions) {
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
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> actions.delete(message, action))
                .show();
    }

    public static void showForwardPicker(
            @NonNull Context context,
            @NonNull List<ConversationSummary> conversations,
            @NonNull java.util.function.Consumer<String> onTarget) {
        DialogForwardBinding ui = DialogForwardBinding.inflate(LayoutInflater.from(context));
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        ui.forwardList.setLayoutManager(new LinearLayoutManager(context));
        final String[] selected = {null};
        ForwardTargetAdapter adapter = new ForwardTargetAdapter(id -> {
            selected[0] = id;
            ui.forwardSend.setEnabled(true);
        });
        ui.forwardList.setAdapter(adapter);
        adapter.submitList(new ArrayList<>(conversations));
        ui.forwardEmpty.setVisibility(conversations.isEmpty() ? View.VISIBLE : View.GONE);
        ui.forwardSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                List<ConversationSummary> filtered = new ArrayList<>();
                String needle = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (ConversationSummary item : conversations) {
                    if (item.getDisplayName() != null && item.getDisplayName().toLowerCase(java.util.Locale.ROOT).contains(needle)) filtered.add(item);
                }
                // A filtered-out recipient must not remain a hidden send target.
                if (filtered.stream().noneMatch(item -> item.getId().equals(selected[0]))) {
                    selected[0] = null;
                    adapter.setSelectedId(null);
                    ui.forwardSend.setEnabled(false);
                }
                ui.forwardEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.submitList(filtered);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        ui.forwardSend.setOnClickListener(v -> {
            if (selected[0] != null && ui.forwardSend.isEnabled()) {
                ui.forwardSend.setEnabled(false);
                dialog.dismiss();
                onTarget.accept(selected[0]);
            }
        });
        dialog.setContentView(ui.getRoot());
        ui.getRoot().getLayoutParams().height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.8f);
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    public static void showAttachmentPicker(@NonNull Context context, @NonNull Runnable visual, @NonNull Runnable document) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.attachment_picker_title)
                .setItems(new CharSequence[]{context.getString(R.string.media_pick_visual), context.getString(R.string.media_pick_document)},
                        (dialog, which) -> { if (which == 0) visual.run(); else document.run(); })
                .show();
    }

    public static void bindReplyBanner(@NonNull View root, @NonNull String text, @NonNull Runnable cancel) {
        TextView preview = root.findViewById(R.id.replyPreview);
        preview.setText(text);
        root.findViewById(R.id.cancelReplyEdit).setContentDescription(root.getContext().getString(R.string.cd_cancel_reply));
        root.findViewById(R.id.cancelReplyEdit).setOnClickListener(v -> cancel.run());
        root.setVisibility(View.VISIBLE);
    }

    public static void bindPinnedBanner(@NonNull View root, @NonNull String text, @NonNull Runnable jump) {
        ((TextView) root.findViewById(R.id.pinnedText)).setText(text);
        root.setOnClickListener(v -> jump.run());
        root.setVisibility(View.VISIBLE);
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("message", text));
    }
}

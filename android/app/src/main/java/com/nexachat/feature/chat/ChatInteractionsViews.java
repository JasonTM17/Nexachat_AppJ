package com.nexachat.feature.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
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

    public static void showMessageActions(
            @NonNull Context context,
            @NonNull ChatMessage message,
            boolean canPin,
            boolean pinned,
            @NonNull RecallWindowRules.DeleteAction deleteAction,
            @NonNull MessageActions actions) {
        List<String> labels = new ArrayList<>();
        List<Runnable> callbacks = new ArrayList<>();
        boolean sent = ChatBubbleRules.isMessageReplyable(message.getStatus()) && !message.isDeleted();
        if (sent) {
            labels.add(context.getString(R.string.action_reply)); callbacks.add(() -> actions.reply(message));
        }
        if (message.getContent() != null && !message.getContent().isBlank() && !message.isDeleted()) {
            labels.add(context.getString(R.string.action_copy)); callbacks.add(() -> copy(context, message.getContent()));
        }
        if (sent && message.getContent() != null && !message.getContent().isBlank()
                && ForwardFilter.canForward(message.getType(), message.isDeleted())) {
            labels.add(context.getString(R.string.action_forward)); callbacks.add(() -> actions.forward(message));
        }
        if (canPin && sent) {
            labels.add(context.getString(pinned ? R.string.action_unpin : R.string.action_pin)); callbacks.add(() -> actions.pin(message));
        }
        if (message.getMine() && sent && "TEXT".equals(message.getType())) {
            labels.add(context.getString(R.string.action_edit)); callbacks.add(() -> actions.edit(message));
        }
        if (deleteAction != RecallWindowRules.DeleteAction.NONE) {
            int actionLabel = switch (deleteAction) {
                case RECALL -> R.string.action_recall;
                case DELETE_FOR_ME -> R.string.action_delete_for_me;
                case ADMIN_DELETE -> R.string.action_admin_delete;
                case NONE -> throw new IllegalStateException("No delete action label");
                default -> throw new IllegalStateException("Unknown delete action");
            };
            labels.add(context.getString(actionLabel));
            callbacks.add(() -> confirmDelete(context, message, deleteAction, actions));
        }
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        LinearLayout content = sheetColumn(context);
        TextView title = new TextView(context);
        title.setText(R.string.message_actions_title);
        title.setTextAppearance(R.style.TextAppearance_NexaChat_TitleMedium);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        content.addView(title);
        if (sent) content.addView(reactionRow(context, emoji -> {
            dialog.dismiss();
            actions.react(message, emoji);
        }));
        for (int i = 0; i < labels.size(); i++) {
            Runnable action = callbacks.get(i);
            MaterialButton button = new MaterialButton(context, null,
                    com.google.android.material.R.attr.borderlessButtonStyle);
            button.setText(labels.get(i));
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setMinHeight(context.getResources().getDimensionPixelSize(R.dimen.min_touch_target));
            button.setTextAppearance(R.style.TextAppearance_NexaChat_BodyLarge);
            button.setTextColor(MaterialColors.getColor(button,
                    i == labels.size() - 1 && deleteAction != RecallWindowRules.DeleteAction.NONE
                            ? com.google.android.material.R.attr.colorError
                            : com.google.android.material.R.attr.colorOnSurface));
            button.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
            content.addView(button, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.show();
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    public static void showReactionPicker(@NonNull Context context, @NonNull java.util.function.Consumer<String> onPick) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        dialog.setContentView(reactionRow(context, emoji -> { dialog.dismiss(); onPick.accept(emoji); }));
        dialog.show();
    }

    private static LinearLayout sheetColumn(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = context.getResources().getDimensionPixelSize(R.dimen.space_md);
        content.setPadding(inset, inset, inset, inset);
        return content;
    }

    private static HorizontalScrollView reactionRow(Context context,
            java.util.function.Consumer<String> onPick) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
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
        scroll.addView(row);
        return scroll;
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

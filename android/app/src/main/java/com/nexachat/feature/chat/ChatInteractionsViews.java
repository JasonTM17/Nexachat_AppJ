package com.nexachat.feature.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.core.ui.ForwardFilter;
import com.nexachat.core.ui.RecallWindowRules;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.chat.adapter.ForwardTargetAdapter;
import java.util.ArrayList;
import java.util.List;

/** Native Android views for chat actions, the composer banners and media picker. */
public final class ChatInteractionsViews {
    private static final String[] REACTIONS = {"👍", "❤️", "😂", "😮", "😢", "🙏"};

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
        boolean sent = message.getStatus() == com.nexachat.data.DeliveryStatus.SENT && !message.isDeleted();
        if (sent) {
            labels.add(context.getString(R.string.action_reply)); callbacks.add(() -> actions.reply(message));
            labels.add("👍"); callbacks.add(() -> showReactionPicker(context, emoji -> actions.react(message, emoji)));
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
        new MaterialAlertDialogBuilder(context)
                .setTitle(message.getContent() == null ? context.getString(R.string.chat_title) : message.getContent())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> callbacks.get(which).run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void showReactionPicker(@NonNull Context context, @NonNull java.util.function.Consumer<String> onPick) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_reaction_picker_title)
                .setItems(REACTIONS, (dialog, which) -> onPick.accept(REACTIONS[which]))
                .show();
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
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 8, 24, 8);
        EditText query = new EditText(context);
        query.setSingleLine(true);
        query.setHint(R.string.forward_search_placeholder);
        root.addView(query);
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        final String[] selected = {null};
        ForwardTargetAdapter adapter = new ForwardTargetAdapter(id -> selected[0] = id);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 520));
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.forward_title)
                .setView(root)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.forward_send, null);
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        adapter.submitList(new ArrayList<>(conversations));
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                List<ConversationSummary> filtered = new ArrayList<>();
                String needle = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (ConversationSummary item : conversations) {
                    if (item.getDisplayName() != null && item.getDisplayName().toLowerCase(java.util.Locale.ROOT).contains(needle)) filtered.add(item);
                }
                adapter.submitList(filtered);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (selected[0] != null) { onTarget.accept(selected[0]); dialog.dismiss(); }
            });
        });
        dialog.show();
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

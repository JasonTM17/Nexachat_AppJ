package com.nexachat.feature.chat.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import com.nexachat.feature.chat.ui.MessageActionListener;
import java.text.DateFormat;
import java.util.List;
import java.util.function.Function;
import com.nexachat.data.ChatColor;
import com.nexachat.data.ChatMessage;

/**
 * Chat stream adapter with date separators and typed message bubbles.
 */
public final class MessageListAdapter extends ListAdapter<MessageListItem, RecyclerView.ViewHolder> {

    /**
     * Repaint-only payload: the row rebinds against the binder's latest
     * adapter-level state without the item-change animation flash.
     */
    private static final Object PAYLOAD_REPAINT = new Object();

    private final MessageBubbleBinder binder;
    @Nullable private String currentUserId;
    private int pausedFailReasonRes;
    @NonNull private ChatColor chatColor = ChatColor.PLUM;

    public MessageListAdapter(
            @NonNull DateFormat timeFormat,
            @NonNull Function<String, ChatMessage> resolveOriginal,
            @NonNull MessageActionListener actionListener
    ) {
        this(timeFormat, resolveOriginal, actionListener, null, null);
    }

    public MessageListAdapter(
            @NonNull DateFormat timeFormat,
            @NonNull Function<String, ChatMessage> resolveOriginal,
            @NonNull MessageActionListener actionListener,
            @Nullable LifecycleOwner lifecycleOwner,
            @Nullable ViewModelStoreOwner storeOwner
    ) {
        super(new MessageListDiffCallback());
        this.binder = new MessageBubbleBinder(
                timeFormat, resolveOriginal, actionListener, lifecycleOwner, storeOwner);
    }

    public void setCurrentUserId(@Nullable String userId) {
        if (java.util.Objects.equals(currentUserId, userId)) return;
        currentUserId = userId;
        binder.setCurrentUserId(userId);
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_REPAINT);
    }

    /** Propagates the conversation-level send pause into row binding. */
    public void setPausedFailReason(@StringRes int reasonRes) {
        if (pausedFailReasonRes == reasonRes) return;
        pausedFailReasonRes = reasonRes;
        binder.setPausedFailReason(reasonRes);
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_REPAINT);
    }

    /** Repaints outgoing bubbles when the Profile chat-color choice changes. */
    public void setChatColor(@NonNull ChatColor color) {
        if (chatColor == color) return;
        chatColor = color;
        binder.setChatColor(color);
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_REPAINT);
    }

    /** Replaces the per-message translation states and rebinds affected rows. */
    public void setTranslations(
            @NonNull java.util.Map<String, com.nexachat.feature.chat.TranslationUiState> next) {
        if (translations.equals(next)) return;
        java.util.Map<String, com.nexachat.feature.chat.TranslationUiState> previous =
                translations;
        translations = next;
        binder.setTranslations(next);
        List<MessageListItem> items = getCurrentList();
        for (int i = 0; i < items.size(); i++) {
            MessageListItem item = items.get(i);
            if (item.getKind() != MessageListItem.Kind.MESSAGE) continue;
            String id = item.getMessage().getId();
            if (!java.util.Objects.equals(previous.get(id), next.get(id))) {
                notifyItemChanged(i, PAYLOAD_REPAINT);
            }
        }
    }

    /** Retry callback for failed translations, keyed by message id. */
    public void setTranslationRetry(
            @Nullable Function<String, Boolean> onRetry) {
        binder.setTranslationRetry(onRetry);
    }

    @NonNull private java.util.Map<String, com.nexachat.feature.chat.TranslationUiState>
            translations = java.util.Map.of();

    @Override
    public int getItemViewType(int position) {
        MessageListItem item = getItem(position);
        if (item.getKind() == MessageListItem.Kind.DATE_SEPARATOR) {
            return MessageViewTypeMapper.VIEW_TYPE_DATE_SEPARATOR;
        }
        if (item.getKind() == MessageListItem.Kind.UNREAD_DIVIDER) {
            return MessageViewTypeMapper.VIEW_TYPE_UNREAD_DIVIDER;
        }
        return MessageViewTypeMapper.viewTypeForMessage(item.getMessage());
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int layout = MessageViewTypeMapper.layoutResForViewType(viewType);
        View row = inflater.inflate(layout, parent, false);
        if (viewType == MessageViewTypeMapper.VIEW_TYPE_DATE_SEPARATOR) {
            return new DateSeparatorViewHolder(row);
        }
        if (viewType == MessageViewTypeMapper.VIEW_TYPE_UNREAD_DIVIDER) {
            return new UnreadDividerViewHolder(row);
        }
        return new MessageViewHolder(row);
    }

    @Override
    public void onBindViewHolder(
            @NonNull RecyclerView.ViewHolder holder,
            int position,
            @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MessageListItem item = getItem(position);
        if (holder instanceof DateSeparatorViewHolder) {
            String label = item.getDateSeparatorLabel() != null ? item.getDateSeparatorLabel() : "";
            binder.bindDateSeparator(holder.itemView, label);
        } else if (holder instanceof UnreadDividerViewHolder) {
            binder.bindUnreadDivider(holder.itemView, item.getUnreadDividerCount());
        } else if (holder instanceof MessageViewHolder) {
            binder.bindMessageRow(holder.itemView, item);
        }
    }

    static final class MessageViewHolder extends RecyclerView.ViewHolder {
        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    static final class DateSeparatorViewHolder extends RecyclerView.ViewHolder {
        DateSeparatorViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    static final class UnreadDividerViewHolder extends RecyclerView.ViewHolder {
        UnreadDividerViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}

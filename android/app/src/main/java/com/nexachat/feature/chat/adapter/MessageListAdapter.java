package com.nexachat.feature.chat.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import com.nexachat.feature.chat.ui.MessageActionListener;
import java.text.DateFormat;
import java.util.function.Function;
import com.nexachat.data.ChatMessage;

/**
 * Chat stream adapter with date separators and typed message bubbles.
 */
public final class MessageListAdapter extends ListAdapter<MessageListItem, RecyclerView.ViewHolder> {

    private final MessageBubbleBinder binder;
    @Nullable private String currentUserId;

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
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        MessageListItem item = getItem(position);
        if (item.getKind() == MessageListItem.Kind.DATE_SEPARATOR) {
            return MessageViewTypeMapper.VIEW_TYPE_DATE_SEPARATOR;
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
        return new MessageViewHolder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MessageListItem item = getItem(position);
        if (holder instanceof DateSeparatorViewHolder) {
            String label = item.getDateSeparatorLabel() != null ? item.getDateSeparatorLabel() : "";
            binder.bindDateSeparator(holder.itemView, label);
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
}

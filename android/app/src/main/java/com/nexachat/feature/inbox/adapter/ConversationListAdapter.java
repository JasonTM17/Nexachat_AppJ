package com.nexachat.feature.inbox.adapter;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.core.ui.components.ChatUiRules;
import com.nexachat.data.ConversationSummary;

/**
 * Inbox conversation rows with unread badge and relative timestamps.
 */
public final class ConversationListAdapter extends ListAdapter<ConversationSummary, ConversationListAdapter.Holder> {

    public interface Listener {
        void onConversationClick(@NonNull String conversationId);
    }

    private final Listener listener;
    @Nullable
    private final String currentUserId;
    @NonNull
    private final String justNowLabel;
    @NonNull
    private final String yesterdayLabel;

    public ConversationListAdapter(
            @NonNull Listener listener,
            @Nullable String currentUserId,
            @NonNull String justNowLabel,
            @NonNull String yesterdayLabel
    ) {
        super(DIFF);
        this.listener = listener;
        this.currentUserId = currentUserId;
        this.justNowLabel = justNowLabel;
        this.yesterdayLabel = yesterdayLabel;
    }

    private static final DiffUtil.ItemCallback<ConversationSummary> DIFF =
            new DiffUtil.ItemCallback<ConversationSummary>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull ConversationSummary oldItem,
                        @NonNull ConversationSummary newItem
                ) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull ConversationSummary oldItem,
                        @NonNull ConversationSummary newItem
                ) {
                    return oldItem.equals(newItem);
                }
            };

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_inbox_conversation, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ConversationSummary conversation = getItem(position);
        holder.bind(conversation, currentUserId, justNowLabel, yesterdayLabel, listener);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final AvatarView avatar;
        private final TextView title;
        private final TextView time;
        private final TextView preview;
        private final TextView unreadBadge;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            title = itemView.findViewById(R.id.title);
            time = itemView.findViewById(R.id.time);
            preview = itemView.findViewById(R.id.preview);
            unreadBadge = itemView.findViewById(R.id.unreadBadge);
        }

        void bind(
                @NonNull ConversationSummary conversation,
                @Nullable String currentUserId,
                @NonNull String justNowLabel,
                @NonNull String yesterdayLabel,
                @NonNull Listener listener
        ) {
            String displayTitle = ConversationPreviewHelper.displayTitle(itemView.getContext(), conversation);
            title.setText(displayTitle);
            avatar.setName(displayTitle);
            avatar.setAvatarUrl(conversation.getDisplayAvatar());

            boolean unread = conversation.getUnreadCount() > 0;
            title.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
            preview.setText(ConversationPreviewHelper.previewText(
                    itemView.getContext(),
                    conversation,
                    currentUserId
            ));
            preview.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);

            Long at = conversation.getLastMessageAtEpochMs();
            if (at != null) {
                time.setVisibility(View.VISIBLE);
                time.setText(ChatUiRules.resolveRelativeTimeLabel(
                        at,
                        System.currentTimeMillis(),
                        justNowLabel,
                        yesterdayLabel
                ));
                time.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
                int timeColor = resolveThemeColor(
                        itemView.getContext(),
                        unread ? com.google.android.material.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant
                );
                time.setTextColor(timeColor);
            } else {
                time.setVisibility(View.GONE);
            }

            if (unread) {
                unreadBadge.setVisibility(View.VISIBLE);
                unreadBadge.setText(String.valueOf(conversation.getUnreadCount()));
            } else {
                unreadBadge.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> listener.onConversationClick(conversation.getId()));
        }

        private static int resolveThemeColor(@NonNull Context context, int attr) {
            TypedValue value = new TypedValue();
            if (!context.getTheme().resolveAttribute(attr, value, true)) {
                return 0;
            }
            if (value.resourceId != 0) {
                return ContextCompat.getColor(context, value.resourceId);
            }
            return value.data;
        }
    }
}

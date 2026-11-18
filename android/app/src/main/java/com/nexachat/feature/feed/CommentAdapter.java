package com.nexachat.feature.feed;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;

/** Flat comment rows under a post; the menu only appears on the caller's own comments. */
public final class CommentAdapter extends ListAdapter<FeedRepository.Comment, CommentAdapter.Holder> {

    /** What the list does when a comment menu is tapped. */
    public interface Listener {
        void onCommentMenu(@NonNull FeedRepository.Comment comment, @NonNull View anchor);
    }

    private static final DiffUtil.ItemCallback<FeedRepository.Comment> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull FeedRepository.Comment oldItem,
                        @NonNull FeedRepository.Comment newItem) {
                    return oldItem.id().equals(newItem.id());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull FeedRepository.Comment oldItem,
                        @NonNull FeedRepository.Comment newItem) {
                    return oldItem.equals(newItem);
                }
            };

    private final Listener listener;

    public CommentAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_feed_comment, parent, false);
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        FeedRepository.Comment comment = getItem(position);
        Context context = holder.itemView.getContext();
        String author = comment.author().displayName() == null ? "" : comment.author().displayName();
        holder.author.setText(author);
        holder.avatar.setName(author);
        holder.avatar.setAvatarUrl(comment.author().avatarUrl());
        holder.avatar.setOnlineStatus(null);
        holder.body.setText(comment.body() == null ? "" : comment.body());
        holder.time.setText(relativeTime(comment.createdAt()));
        holder.menu.setVisibility(comment.mine() ? View.VISIBLE : View.GONE);
        holder.menu.setOnClickListener(anchor -> listener.onCommentMenu(comment, anchor));
        holder.itemView.setContentDescription(context.getString(
                R.string.feed_comment_cd, author, comment.body() == null ? "" : comment.body()));
    }

    private static CharSequence relativeTime(@Nullable String createdAt) {
        long epochMs = FeedTimeRules.epochMillis(createdAt);
        if (epochMs <= 0L) {
            return "";
        }
        return DateUtils.getRelativeTimeSpanString(
                epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final AvatarView avatar;
        final TextView author;
        final TextView body;
        final TextView time;
        final ImageButton menu;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.commentAvatar);
            author = itemView.findViewById(R.id.commentAuthor);
            body = itemView.findViewById(R.id.commentBody);
            time = itemView.findViewById(R.id.commentTime);
            menu = itemView.findViewById(R.id.commentMenu);
        }
    }
}

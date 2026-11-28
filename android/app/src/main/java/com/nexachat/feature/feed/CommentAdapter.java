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

/**
 * Flat comment cards under a post: header-aligned author + time + edited label,
 * author tap target, and overflow menu shown on every comment.
 */
public final class CommentAdapter extends ListAdapter<FeedRepository.Comment, CommentAdapter.Holder> {

    /** What the list does when a comment menu or author header is tapped. */
    public interface Listener {
        void onCommentMenu(@NonNull FeedRepository.Comment comment, @NonNull View anchor);

        default void onOpenAuthor(@NonNull FeedRepository.Author author, boolean isMine) { }
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
        holder.time.setText(relativeTime(context, comment.createdAt()));
        boolean edited = comment.editedAt() != null && !comment.editedAt().isBlank();
        holder.edited.setVisibility(edited ? View.VISIBLE : View.GONE);
        holder.body.setText(comment.body() == null ? "" : comment.body());
        holder.menu.setVisibility(View.VISIBLE);
        holder.menu.setOnClickListener(anchor -> listener.onCommentMenu(comment, anchor));
        holder.authorHeader.setContentDescription(author);
        holder.authorHeader.setOnClickListener(
                ignored -> listener.onOpenAuthor(comment.author(), comment.mine()));
        holder.itemView.setContentDescription(context.getString(
                R.string.feed_comment_cd, author, comment.body() == null ? "" : comment.body()));
    }

    private static CharSequence relativeTime(Context context, @Nullable String createdAt) {
        long epochMs = FeedTimeRules.epochMillis(createdAt);
        if (epochMs <= 0L) {
            return "";
        }
        if (FeedTimeRules.isJustNow(epochMs, System.currentTimeMillis())) {
            return context.getString(R.string.time_just_now);
        }
        return DateUtils.getRelativeTimeSpanString(
                epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View authorHeader;
        final AvatarView avatar;
        final TextView author;
        final TextView time;
        final TextView edited;
        final TextView body;
        final ImageButton menu;

        Holder(@NonNull View itemView) {
            super(itemView);
            authorHeader = itemView.findViewById(R.id.commentAuthorHeader);
            avatar = itemView.findViewById(R.id.commentAvatar);
            author = itemView.findViewById(R.id.commentAuthor);
            time = itemView.findViewById(R.id.commentTime);
            edited = itemView.findViewById(R.id.commentEdited);
            body = itemView.findViewById(R.id.commentBody);
            menu = itemView.findViewById(R.id.commentMenu);
        }
    }
}

package com.nexachat.feature.feed;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.nexachat.R;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.ui.components.AvatarView;
import java.util.List;

/** Public feed rows: author, body, optional image, reaction pills and the comment count. */
public final class PostAdapter extends ListAdapter<FeedRepository.Post, PostAdapter.Holder> {

    /** What the list does when a row is opened, reacted to or its menu is tapped. */
    public interface Listener {
        void onOpenPost(@NonNull FeedRepository.Post post);

        void onReact(@NonNull FeedRepository.Post post);

        void onPostMenu(@NonNull FeedRepository.Post post, @NonNull View anchor);

        void onReactionTap(@NonNull FeedRepository.Post post, @NonNull String emoji);
    }

    private static final DiffUtil.ItemCallback<FeedRepository.Post> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull FeedRepository.Post oldItem,
                        @NonNull FeedRepository.Post newItem) {
                    return oldItem.id().equals(newItem.id());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull FeedRepository.Post oldItem,
                        @NonNull FeedRepository.Post newItem) {
                    return oldItem.equals(newItem);
                }
            };

    private final Listener listener;

    public PostAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_feed_post, parent, false);
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        FeedRepository.Post post = getItem(position);
        Context context = holder.itemView.getContext();
        String author = post.author().displayName() == null ? "" : post.author().displayName();
        holder.author.setText(author);
        holder.avatar.setName(author);
        holder.avatar.setAvatarUrl(post.author().avatarUrl());
        holder.avatar.setOnlineStatus(null);
        holder.time.setText(relativeTime(context, post.createdAt()));

        String body = post.body();
        boolean hasBody = body != null && !body.isBlank();
        holder.body.setVisibility(hasBody ? View.VISIBLE : View.GONE);
        if (hasBody) {
            holder.body.setText(body);
        }

        bindImage(holder.image, post.image());
        bindReactions(holder, post);

        holder.comments.setText(context.getResources().getQuantityString(
                R.plurals.feed_comment_count, post.commentCount(), post.commentCount()));
        holder.comments.setOnClickListener(ignored -> listener.onOpenPost(post));
        holder.itemView.setOnClickListener(ignored -> listener.onOpenPost(post));
        holder.react.setOnClickListener(ignored -> listener.onReact(post));
        holder.menu.setOnClickListener(anchor -> listener.onPostMenu(post, anchor));
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        Glide.with(holder.image).clear(holder.image);
        super.onViewRecycled(holder);
    }

    private static void bindImage(@NonNull ImageView image, @Nullable FeedRepository.Image model) {
        if (model == null || model.url() == null) {
            Glide.with(image).clear(image);
            image.setVisibility(View.GONE);
            return;
        }
        image.setVisibility(View.VISIBLE);
        Glide.with(image)
                .load(AppConfig.absoluteMediaUrl(model.url()))
                .centerCrop()
                .into(image);
    }

    private void bindReactions(@NonNull Holder holder, @NonNull FeedRepository.Post post) {
        holder.reactions.removeAllViews();
        List<FeedRepository.Reaction> reactions = post.reactions();
        if (reactions.isEmpty()) {
            holder.reactions.setVisibility(View.GONE);
            return;
        }
        holder.reactions.setVisibility(View.VISIBLE);
        Context context = holder.itemView.getContext();
        int gap = context.getResources().getDimensionPixelSize(R.dimen.space_xs);
        int minHeight = context.getResources()
                .getDimensionPixelSize(R.dimen.feed_reaction_pill_min_height);
        int horizontal = context.getResources().getDimensionPixelSize(R.dimen.space_sm);
        for (int i = 0; i < reactions.size(); i++) {
            FeedRepository.Reaction reaction = reactions.get(i);
            TextView pill = new TextView(context);
            pill.setText(context.getString(
                    R.string.feed_reaction_pill, reaction.emoji(), reaction.count()));
            pill.setContentDescription(context.getResources().getQuantityString(
                    R.plurals.feed_reaction_count_cd,
                    reaction.count(), reaction.emoji(), reaction.count()));
            TextViewCompat.setTextAppearance(pill, R.style.TextAppearance_NexaChat_LabelMedium);
            pill.setGravity(Gravity.CENTER);
            pill.setMinHeight(minHeight);
            pill.setPadding(horizontal, 0, horizontal, 0);
            pill.setBackgroundResource(reaction.mine()
                    ? R.drawable.bg_feed_reaction_pill_mine
                    : R.drawable.bg_feed_reaction_pill);
            pill.setOnClickListener(ignored -> listener.onReactionTap(post, reaction.emoji()));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                params.setMarginStart(gap);
            }
            holder.reactions.addView(pill, params);
        }
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
        final AvatarView avatar;
        final TextView author;
        final TextView time;
        final TextView body;
        final ImageView image;
        final LinearLayout reactions;
        final MaterialButton react;
        final MaterialButton comments;
        final ImageButton menu;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.postAvatar);
            author = itemView.findViewById(R.id.postAuthor);
            time = itemView.findViewById(R.id.postTime);
            body = itemView.findViewById(R.id.postBody);
            image = itemView.findViewById(R.id.postImage);
            reactions = itemView.findViewById(R.id.postReactions);
            react = itemView.findViewById(R.id.postReact);
            comments = itemView.findViewById(R.id.postComments);
            menu = itemView.findViewById(R.id.postMenu);
        }
    }
}

package com.nexachat.feature.feed;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.nexachat.R;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.ui.components.AvatarView;
import java.util.List;

/**
 * Public feed rows: author header, edited badge, optional image or Media3 video player,
 * reaction pills, and comment count.
 */
public final class PostAdapter extends ListAdapter<FeedRepository.Post, PostAdapter.Holder> {

    /** What the list does when a row, author, reaction, or menu is tapped. */
    public interface Listener {
        void onOpenPost(@NonNull FeedRepository.Post post);

        void onReact(@NonNull FeedRepository.Post post);

        void onPostMenu(@NonNull FeedRepository.Post post, @NonNull View anchor);

        void onReactionTap(@NonNull FeedRepository.Post post, @NonNull String emoji);

        default void onOpenAuthor(@NonNull FeedRepository.Author author, boolean isMine) { }
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
    @Nullable private final FeedVideoController videoController;

    public PostAdapter(@NonNull Listener listener) {
        this(listener, null);
    }

    public PostAdapter(
            @NonNull Listener listener, @Nullable FeedVideoController videoController) {
        super(DIFF);
        this.listener = listener;
        this.videoController = videoController;
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
        boolean edited = post.editedAt() != null && !post.editedAt().isBlank();
        holder.edited.setVisibility(edited ? View.VISIBLE : View.GONE);

        String body = post.body();
        boolean hasBody = body != null && !body.isBlank();
        holder.body.setVisibility(hasBody ? View.VISIBLE : View.GONE);
        if (hasBody) {
            holder.body.setText(body);
        }

        bindImage(holder.image, post.image());
        bindVideo(holder, post);
        bindReactions(holder, post);

        holder.comments.setText(post.commentCount() == 0
                ? context.getString(R.string.feed_comment_count_zero)
                : context.getResources().getQuantityString(R.plurals.feed_comment_count,
                        post.commentCount(), post.commentCount()));
        holder.comments.setOnClickListener(ignored -> listener.onOpenPost(post));
        holder.itemView.setOnClickListener(ignored -> listener.onOpenPost(post));
        holder.authorHeader.setOnClickListener(
                ignored -> listener.onOpenAuthor(post.author(), post.mine()));
        holder.react.setOnClickListener(ignored -> listener.onReact(post));
        holder.menu.setOnClickListener(anchor -> listener.onPostMenu(post, anchor));
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        Glide.with(holder.image).clear(holder.image);
        if (videoController != null) {
            videoController.onRecycled(holder, holder.videoPlayer);
        } else {
            holder.videoPlayer.setPlayer(null);
        }
        super.onViewRecycled(holder);
    }

    private static void bindImage(@NonNull ImageView image, @Nullable FeedRepository.Image model) {
        String absolute = model == null ? null : AppConfig.absoluteMediaUrl(model.url());
        if (absolute == null) {
            // No url, or an absolute URL off the API origin: nothing to load.
            Glide.with(image).clear(image);
            image.setVisibility(View.GONE);
            return;
        }
        image.setClipToOutline(true);
        image.setVisibility(View.VISIBLE);
        Glide.with(image)
                .load(absolute)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .centerCrop()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(
                            @Nullable GlideException error,
                            Object model,
                            Target<Drawable> target,
                            boolean isFirstResource) {
                        // A failed fetch would otherwise leave a blank coloured block
                        // the height of the image area; collapse the row instead.
                        image.setVisibility(View.GONE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(
                            Drawable resource,
                            Object model,
                            Target<Drawable> target,
                            DataSource dataSource,
                            boolean isFirstResource) {
                        return false;
                    }
                })
                .into(image);
    }

    private void bindVideo(@NonNull Holder holder, @NonNull FeedRepository.Post post) {
        FeedRepository.Video video = post.video();
        String absolute = video == null ? null : AppConfig.absoluteMediaUrl(video.url());
        if (absolute == null || videoController == null) {
            holder.videoPlayer.setPlayer(null);
            holder.videoContainer.setVisibility(View.GONE);
            return;
        }
        holder.videoContainer.setVisibility(View.VISIBLE);
        videoController.bind(
                holder,
                post.id(),
                absolute,
                holder.videoContainer,
                holder.videoPlayer,
                holder.videoPlayPause,
                holder.videoMute,
                holder.videoBuffering,
                holder.videoError,
                holder.videoRetry);
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
        int pillHeight = context.getResources()
                .getDimensionPixelSize(R.dimen.feed_reaction_pill_min_height);
        int touchTarget = context.getResources().getDimensionPixelSize(R.dimen.min_touch_target);
        int inset = FeedRules.reactionPillInset(touchTarget, pillHeight);
        // The tap area grows by the inset above the pill; take it from the row margin so the pill stays put.
        LinearLayout.LayoutParams rowParams =
                (LinearLayout.LayoutParams) holder.reactions.getLayoutParams();
        rowParams.topMargin = Math.max(
                0, context.getResources().getDimensionPixelSize(R.dimen.space_sm) - inset);
        holder.reactions.setLayoutParams(rowParams);
        int horizontal = context.getResources().getDimensionPixelSize(R.dimen.space_sm);
        int mineColor = MaterialColors.getColor(
                holder.itemView, com.google.android.material.R.attr.colorOnPrimaryContainer);
        int otherColor = MaterialColors.getColor(
                holder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int i = 0; i < reactions.size(); i++) {
            FeedRepository.Reaction reaction = reactions.get(i);
            TextView pill = new TextView(context);
            pill.setText(context.getString(
                    R.string.feed_reaction_pill, reaction.emoji(), reaction.count()));
            pill.setContentDescription(context.getResources().getQuantityString(
                    R.plurals.feed_reaction_count_cd,
                    reaction.count(), reaction.emoji(), reaction.count()));
            TextViewCompat.setTextAppearance(pill, R.style.TextAppearance_NexaChat_LabelMedium);
            pill.setTextColor(reaction.mine() ? mineColor : otherColor);
            pill.setGravity(Gravity.CENTER);
            pill.setMinHeight(pillHeight + 2 * inset);
            Drawable look = ContextCompat.getDrawable(context, reaction.mine()
                    ? R.drawable.bg_feed_reaction_pill_mine
                    : R.drawable.bg_feed_reaction_pill);
            pill.setBackground(new InsetDrawable(look, 0, inset, 0, inset));
            // After the background: an inset drawable reports padding that setBackground applies.
            pill.setPadding(horizontal, inset, horizontal, inset);
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
        final View authorHeader;
        final AvatarView avatar;
        final TextView author;
        final TextView time;
        final TextView edited;
        final TextView body;
        final ImageView image;
        final FrameLayout videoContainer;
        final PlayerView videoPlayer;
        final ProgressBar videoBuffering;
        final ImageButton videoPlayPause;
        final ImageButton videoMute;
        final View videoError;
        final MaterialButton videoRetry;
        final LinearLayout reactions;
        final MaterialButton react;
        final MaterialButton comments;
        final ImageButton menu;

        Holder(@NonNull View itemView) {
            super(itemView);
            authorHeader = itemView.findViewById(R.id.postAuthorHeader);
            avatar = itemView.findViewById(R.id.postAvatar);
            author = itemView.findViewById(R.id.postAuthor);
            time = itemView.findViewById(R.id.postTime);
            edited = itemView.findViewById(R.id.postEdited);
            body = itemView.findViewById(R.id.postBody);
            image = itemView.findViewById(R.id.postImage);
            videoContainer = itemView.findViewById(R.id.postVideoContainer);
            videoPlayer = itemView.findViewById(R.id.postVideoPlayer);
            videoBuffering = itemView.findViewById(R.id.postVideoBuffering);
            videoPlayPause = itemView.findViewById(R.id.postVideoPlayPause);
            videoMute = itemView.findViewById(R.id.postVideoMute);
            videoError = itemView.findViewById(R.id.postVideoError);
            videoRetry = itemView.findViewById(R.id.postVideoRetry);
            reactions = itemView.findViewById(R.id.postReactions);
            react = itemView.findViewById(R.id.postReact);
            comments = itemView.findViewById(R.id.postComments);
            menu = itemView.findViewById(R.id.postMenu);
        }
    }
}

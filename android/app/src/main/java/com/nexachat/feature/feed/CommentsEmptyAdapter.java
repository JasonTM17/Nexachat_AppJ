package com.nexachat.feature.feed;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.databinding.ItemFeedCommentsEmptyBinding;

/**
 * Single-row footer under a post's comment list: shown only while the post is
 * loaded, not loading, and has no comments yet. The CTA focuses the composer.
 */
final class CommentsEmptyAdapter extends RecyclerView.Adapter<CommentsEmptyAdapter.Holder> {

    private boolean shown;
    private final Runnable onFocusComposer;

    CommentsEmptyAdapter(@NonNull Runnable onFocusComposer) {
        this.onFocusComposer = onFocusComposer;
    }

    void setShown(boolean value) {
        if (shown == value) {
            return;
        }
        shown = value;
        if (shown) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    @Override
    public int getItemCount() {
        return shown ? 1 : 0;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFeedCommentsEmptyBinding binding = ItemFeedCommentsEmptyBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.binding.commentsEmptyAction.setOnClickListener(v -> onFocusComposer.run());
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemFeedCommentsEmptyBinding binding;

        Holder(@NonNull ItemFeedCommentsEmptyBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

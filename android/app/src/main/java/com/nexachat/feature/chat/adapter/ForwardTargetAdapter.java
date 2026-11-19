package com.nexachat.feature.chat.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.inbox.adapter.ConversationPreviewHelper;
import java.util.List;
import java.util.Objects;

/**
 * Forward-message target picker list (dialog_forward.xml).
 */
public final class ForwardTargetAdapter extends ListAdapter<ConversationSummary, ForwardTargetAdapter.Holder> {

    public interface Listener {
        void onTargetSelected(@NonNull String conversationId);
    }

    /** Repaints row selection state only; titles and avatars stay untouched. */
    private static final Object PAYLOAD_SELECTION = new Object();

    private final Listener listener;
    @Nullable
    private String selectedId;

    public ForwardTargetAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    public void setSelectedId(@Nullable String selectedId) {
        if (Objects.equals(this.selectedId, selectedId)) {
            return;
        }
        String previous = this.selectedId;
        this.selectedId = selectedId;
        notifySelectionChanged(previous, selectedId);
    }

    @Nullable
    public String getSelectedId() {
        return selectedId;
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
                .inflate(R.layout.item_forward_target, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position), selectedId, listener, this::select);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position), selectedId);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    private void select(@NonNull String conversationId) {
        if (conversationId.equals(selectedId)) {
            return;
        }
        String previous = selectedId;
        selectedId = conversationId;
        notifySelectionChanged(previous, conversationId);
    }

    private void notifySelectionChanged(@Nullable String previous, @Nullable String next) {
        if (Objects.equals(previous, next)) {
            return;
        }
        notifySelectionRow(previous);
        notifySelectionRow(next);
    }

    private void notifySelectionRow(@Nullable String conversationId) {
        if (conversationId == null) {
            return;
        }
        for (int index = 0; index < getItemCount(); index++) {
            if (getItem(index).getId().equals(conversationId)) {
                notifyItemChanged(index, PAYLOAD_SELECTION);
                return;
            }
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final AvatarView avatar;
        private final TextView title;
        private final MaterialRadioButton radio;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            title = itemView.findViewById(R.id.title);
            radio = itemView.findViewById(R.id.selectionRadio);
        }

        void bind(
                @NonNull ConversationSummary conversation,
                @Nullable String selectedId,
                @NonNull Listener listener,
                @NonNull SelectionNotifier notifier
        ) {
            String label = ConversationPreviewHelper.displayTitle(itemView.getContext(), conversation);
            title.setText(label);
            avatar.setName(label);
            avatar.setAvatarUrl(conversation.getDisplayAvatar());
            bindSelection(conversation, selectedId);
            itemView.setOnClickListener(v -> {
                notifier.onSelected(conversation.getId());
                listener.onTargetSelected(conversation.getId());
            });
            itemView.setContentDescription(label);
        }

        void bindSelection(@NonNull ConversationSummary conversation, @Nullable String selectedId) {
            boolean selected = conversation.getId().equals(selectedId);
            radio.setChecked(selected);
            itemView.setSelected(selected);
            androidx.core.view.ViewCompat.setStateDescription(itemView,
                    itemView.getContext().getString(selected ? R.string.forward_selected : R.string.forward_not_selected));
        }
    }

    interface SelectionNotifier {
        void onSelected(@NonNull String conversationId);
    }
}

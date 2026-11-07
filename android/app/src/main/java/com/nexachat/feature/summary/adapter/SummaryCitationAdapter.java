package com.nexachat.feature.summary.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.nexachat.R;

/**
 * Horizontal citation chips for the summary bottom sheet.
 */
public final class SummaryCitationAdapter extends ListAdapter<SummaryCitationItem, SummaryCitationAdapter.Holder> {

    public interface Listener {
        void onCitationClick(@NonNull String messageId);
    }

    private final Listener listener;

    public SummaryCitationAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<SummaryCitationItem> DIFF =
            new DiffUtil.ItemCallback<SummaryCitationItem>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull SummaryCitationItem oldItem,
                        @NonNull SummaryCitationItem newItem
                ) {
                    return oldItem.getMessageId().equals(newItem.getMessageId());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull SummaryCitationItem oldItem,
                        @NonNull SummaryCitationItem newItem
                ) {
                    return oldItem.equals(newItem);
                }
            };

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_summary_citation, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final Chip chip;

        Holder(@NonNull View itemView) {
            super(itemView);
            chip = itemView.findViewById(R.id.citationChip);
        }

        void bind(@NonNull SummaryCitationItem item, @NonNull Listener listener) {
            chip.setText(item.getChipLabel());
            chip.setEnabled(item.isJumpable());
            chip.setClickable(item.isJumpable());
            int bg = item.isJumpable()
                    ? R.color.brand_indigo_container
                    : R.color.bubble_other;
            chip.setChipBackgroundColor(
                    ContextCompat.getColorStateList(itemView.getContext(), bg)
            );
            chip.setOnClickListener(item.isJumpable()
                    ? v -> listener.onCitationClick(item.getMessageId())
                    : null);
        }
    }
}

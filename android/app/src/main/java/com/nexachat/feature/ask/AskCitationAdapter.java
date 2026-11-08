package com.nexachat.feature.ask;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.databinding.ItemAskCitationBinding;
import java.util.Objects;

/** Displays citation snippets without sender names or message timestamps. */
public final class AskCitationAdapter
        extends ListAdapter<AskCitation, AskCitationAdapter.CitationHolder> {
    public interface Listener {
        void onCitationClick(@NonNull AskCitationJump target);
    }

    private static final DiffUtil.ItemCallback<AskCitation> DIFF =
            new DiffUtil.ItemCallback<AskCitation>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull AskCitation oldItem,
                        @NonNull AskCitation newItem) {
                    return Objects.equals(oldItem.getMessageId(), newItem.getMessageId());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull AskCitation oldItem,
                        @NonNull AskCitation newItem) {
                    return Objects.equals(oldItem.getMessageId(), newItem.getMessageId())
                            && Objects.equals(oldItem.getSeq(), newItem.getSeq())
                            && Objects.equals(oldItem.getSnippet(), newItem.getSnippet());
                }
            };

    private final Listener listener;

    public AskCitationAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @NonNull
    @Override
    public CitationHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAskCitationBinding binding = ItemAskCitationBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new CitationHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull CitationHolder holder, int position) {
        AskCitation citation = getItem(position);
        String snippet = citation.getSnippet() == null ? "" : citation.getSnippet();
        AskCitationJump target = AskCitationJump.from(citation);
        holder.binding.citationButton.setText(snippet);
        holder.binding.citationButton.setContentDescription(
                holder.binding.getRoot().getContext().getString(
                        R.string.ask_citation_accessibility, snippet));
        holder.binding.citationButton.setEnabled(target != null);
        holder.binding.citationButton.setOnClickListener(view -> {
            if (target != null) {
                listener.onCitationClick(target);
            }
        });
    }

    static final class CitationHolder extends RecyclerView.ViewHolder {
        private final ItemAskCitationBinding binding;

        CitationHolder(@NonNull ItemAskCitationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

package com.nexachat.feature.archive;

import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.databinding.ItemArchiveRowBinding;
import java.util.Objects;

/** Accessible file or link list for the active archive tab. */
public final class ArchiveListAdapter
        extends ListAdapter<ArchivePage.Item, ArchiveListAdapter.Holder> {

    private final ArchiveType type;
    private final Listener listener;
    @Nullable private ArchiveUiState state;

    public ArchiveListAdapter(@NonNull ArchiveType type, @NonNull Listener listener) {
        super(DIFF);
        if (type == ArchiveType.MEDIA) {
            throw new IllegalArgumentException("media uses the grid adapter");
        }
        this.type = type;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void setState(@NonNull ArchiveUiState nextState) {
        state = nextState;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemArchiveRowBinding binding = ItemArchiveRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ArchivePage.Item item = getItem(position);
        ArchiveUiState currentState = state;
        if (currentState == null) {
            return;
        }
        ItemArchiveRowBinding binding = holder.binding;
        ArchivePage.Attachment attachment = item.getAttachment();
        boolean file = type == ArchiveType.FILE;
        String title = file
                ? attachment == null || isBlank(attachment.getFileName())
                        ? binding.getRoot().getContext().getString(R.string.archive_tab_files)
                        : attachment.getFileName()
                : item.getLink() == null || isBlank(item.getLink().getUrl())
                        ? binding.getRoot().getContext().getString(R.string.archive_tab_links)
                        : item.getLink().getUrl();
        binding.archiveRowTitle.setText(title);
        binding.archiveRowIcon.setVisibility(View.VISIBLE);
        binding.archiveRowIcon.setImageResource(file ? R.drawable.ic_attach_file : R.drawable.ic_language);
        String details = ArchiveText.details(binding.getRoot().getContext(), currentState, item);
        if (file && attachment != null && attachment.getSizeBytes() != null
                && attachment.getSizeBytes() >= 0L) {
            details = binding.getRoot().getContext().getString(
                    R.string.archive_file_details,
                    details,
                    Formatter.formatShortFileSize(
                            binding.getRoot().getContext(), attachment.getSizeBytes()));
        }
        binding.archiveRowDetails.setText(details);
        binding.getRoot().setContentDescription(ArchiveText.accessibilityDescription(
                binding.getRoot().getContext(), currentState, title, item));
        binding.archiveRowOptions.setContentDescription(
                binding.getRoot().getContext().getString(R.string.archive_item_options_for, title));
        binding.getRoot().setOnClickListener(view -> listener.onOpen(item));
        binding.archiveRowOptions.setOnClickListener(view -> listener.onOptions(item, view));
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static final DiffUtil.ItemCallback<ArchivePage.Item> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull ArchivePage.Item oldItem,
                        @NonNull ArchivePage.Item newItem) {
                    return oldItem.stableKey().equals(newItem.stableKey());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull ArchivePage.Item oldItem,
                        @NonNull ArchivePage.Item newItem) {
                    return oldItem.equals(newItem);
                }
            };

    public interface Listener {
        void onOpen(@NonNull ArchivePage.Item item);

        void onOptions(@NonNull ArchivePage.Item item, @NonNull View anchor);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final ItemArchiveRowBinding binding;

        Holder(ItemArchiveRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

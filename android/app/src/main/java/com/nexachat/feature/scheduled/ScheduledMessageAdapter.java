package com.nexachat.feature.scheduled;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.databinding.ItemScheduledMessageBinding;
import java.util.Objects;

/**
 * Scheduled rows for the caller's own pending/failed/recently-sent messages.
 * The overflow affordance only appears while the row can still be cancelled.
 */
public final class ScheduledMessageAdapter
        extends ListAdapter<ScheduledMessage, ScheduledMessageAdapter.ScheduledViewHolder> {

    public interface Listener {
        void onMenu(@NonNull ScheduledMessage message, @NonNull View anchor);
    }

    private static final DiffUtil.ItemCallback<ScheduledMessage> DIFF =
            new DiffUtil.ItemCallback<ScheduledMessage>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull ScheduledMessage oldItem, @NonNull ScheduledMessage newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull ScheduledMessage oldItem, @NonNull ScheduledMessage newItem) {
                    return oldItem.equals(newItem);
                }
            };

    private final Listener listener;

    public ScheduledMessageAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @NonNull
    @Override
    public ScheduledViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemScheduledMessageBinding binding = ItemScheduledMessageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ScheduledViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ScheduledViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static final class ScheduledViewHolder extends RecyclerView.ViewHolder {
        private final ItemScheduledMessageBinding binding;

        ScheduledViewHolder(@NonNull ItemScheduledMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull ScheduledMessage message, @NonNull Listener listener) {
            android.content.Context context = binding.getRoot().getContext();
            String body = message.getBody().isBlank()
                    ? context.getString(R.string.scheduled_body_unavailable)
                    : message.getBody();
            binding.scheduledBody.setText(body);
            binding.scheduledTime.setText(
                    ScheduledMessageText.dateTime(context, message.getSendAt()));
            binding.scheduledStatus.setText(
                    ScheduledMessageText.statusLine(context, message));
            binding.scheduledStatus.setTextColor(statusColor(context, message));
            binding.scheduledMenu.setVisibility(
                    message.isCancelable() ? View.VISIBLE : View.GONE);
            binding.scheduledMenu.setOnClickListener(anchor ->
                    listener.onMenu(message, binding.scheduledMenu));
        }

        private static int statusColor(
                @NonNull android.content.Context context, @NonNull ScheduledMessage message) {
            int attr = message.getStatus() == ScheduledMessageStatus.FAILED
                    ? com.google.android.material.R.attr.colorError
                    : com.google.android.material.R.attr.colorOnSurfaceVariant;
            android.util.TypedValue value = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(attr, value, true)) {
                return value.resourceId != 0
                        ? androidx.core.content.ContextCompat.getColor(context, value.resourceId)
                        : value.data;
            }
            return androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary_light);
        }
    }
}

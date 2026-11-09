package com.nexachat.feature.reminder;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.databinding.ItemReminderBinding;
import java.util.Objects;

/** Upcoming reminder rows with an edit/delete menu only when the server allows it. */
public final class ReminderAdapter
        extends ListAdapter<Reminder, ReminderAdapter.ReminderViewHolder> {

    public interface Listener {
        void onMenu(@NonNull Reminder reminder, @NonNull View anchor);
    }

    private static final DiffUtil.ItemCallback<Reminder> DIFF =
            new DiffUtil.ItemCallback<Reminder>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull Reminder oldItem, @NonNull Reminder newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull Reminder oldItem, @NonNull Reminder newItem) {
                    return oldItem.equals(newItem);
                }
            };

    private final Listener listener;

    public ReminderAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @NonNull
    @Override
    public ReminderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemReminderBinding binding = ItemReminderBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ReminderViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ReminderViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static final class ReminderViewHolder extends RecyclerView.ViewHolder {
        private final ItemReminderBinding binding;

        ReminderViewHolder(@NonNull ItemReminderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Reminder reminder, @NonNull Listener listener) {
            android.content.Context context = binding.getRoot().getContext();
            binding.reminderTitle.setText(reminder.getTitle());
            binding.reminderTime.setText(ReminderText.dateTime(context, reminder.getRemindAt()));
            binding.reminderDetails.setText(details(context, reminder));
            binding.reminderMenu.setVisibility(reminder.isCanEdit() ? View.VISIBLE : View.GONE);
            binding.reminderMenu.setOnClickListener(anchor ->
                    listener.onMenu(reminder, binding.reminderMenu));
        }

        private static String details(android.content.Context context, Reminder reminder) {
            String repeat = reminder.getRepeat() == ReminderRepeat.NONE
                    ? ""
                    : context.getString(
                            R.string.reminder_repeat_value,
                            context.getString(ReminderText.repeatLabel(reminder.getRepeat())));
            String creator = reminder.getCreatedByName() == null
                    || reminder.getCreatedByName().isBlank()
                    ? ""
                    : context.getString(
                            R.string.reminder_created_by, reminder.getCreatedByName());
            if (repeat.isEmpty()) {
                return creator;
            }
            if (creator.isEmpty()) {
                return repeat;
            }
            return repeat + context.getString(R.string.meta_separator) + creator;
        }
    }
}

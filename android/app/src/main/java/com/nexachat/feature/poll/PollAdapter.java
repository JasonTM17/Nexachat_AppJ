package com.nexachat.feature.poll;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.databinding.ItemPollBinding;
import com.nexachat.databinding.ItemPollOptionMultipleBinding;
import com.nexachat.databinding.ItemPollOptionSingleBinding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Poll cards with vote controls, results, and creator/admin management. */
public final class PollAdapter extends ListAdapter<PollAdapter.Row, PollAdapter.PollViewHolder> {

    public interface Listener {
        void onToggle(@NonNull String pollId, @NonNull String optionId);

        void onVote(@NonNull String pollId);

        void onRetract(@NonNull String pollId);

        void onManage(@NonNull Poll poll, @NonNull View anchor);
    }

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<Row>() {
        @Override
        public boolean areItemsTheSame(@NonNull Row oldItem, @NonNull Row newItem) {
            return oldItem.poll.getId().equals(newItem.poll.getId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull Row oldItem, @NonNull Row newItem) {
            return oldItem.equals(newItem);
        }
    };

    private final Listener listener;

    public PollAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @NonNull
    @Override
    public PollViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PollViewHolder(ItemPollBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PollViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    /** Immutable presentation input for one card. */
    public static final class Row {
        private final Poll poll;
        private final List<String> selection;
        private final Map<String, String> voterNames;
        private final boolean canManage;
        private final boolean voting;
        private final boolean closed;

        public Row(
                @NonNull Poll poll,
                @NonNull List<String> selection,
                @NonNull Map<String, String> voterNames,
                boolean canManage,
                boolean voting,
                boolean closed) {
            this.poll = poll;
            this.selection = List.copyOf(selection);
            this.voterNames = Map.copyOf(voterNames);
            this.canManage = canManage;
            this.voting = voting;
            this.closed = closed;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Row that)) {
                return false;
            }
            return canManage == that.canManage
                    && voting == that.voting
                    && closed == that.closed
                    && poll.equals(that.poll)
                    && selection.equals(that.selection)
                    && voterNames.equals(that.voterNames);
        }

        @Override
        public int hashCode() {
            return Objects.hash(poll, selection, voterNames, canManage, voting, closed);
        }
    }

    static final class PollViewHolder extends RecyclerView.ViewHolder {
        private final ItemPollBinding binding;

        PollViewHolder(@NonNull ItemPollBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Row row, @NonNull Listener listener) {
            Context context = binding.getRoot().getContext();
            Poll poll = row.poll;
            boolean closed = row.closed;
            binding.pollQuestion.setText(poll.getQuestion());
            binding.pollMeta.setText(meta(context, poll, closed));
            binding.pollManage.setVisibility(row.canManage ? View.VISIBLE : View.GONE);
            binding.pollManage.setOnClickListener(anchor ->
                    listener.onManage(poll, binding.pollManage));
            binding.pollOptions.removeAllViews();
            int maxCount = 0;
            for (PollOption option : poll.getOptions()) {
                maxCount = Math.max(maxCount, option.getVoteCount());
            }
            for (PollOption option : poll.getOptions()) {
                addOption(context, poll, option, row, closed, maxCount, listener);
            }
            binding.pollVoterCount.setText(
                    com.nexachat.res.QuantityStrings.pollVoterCount(
                            context.getResources(), poll.getVoterCount()));
            boolean open = !closed;
            boolean dirty = !sameSelection(row.selection, poll.getMyOptionIds());
            binding.pollVote.setVisibility(open ? View.VISIBLE : View.GONE);
            binding.pollVote.setEnabled(open && !row.voting && dirty && !row.selection.isEmpty());
            binding.pollVote.setOnClickListener(ignored -> listener.onVote(poll.getId()));
            binding.pollRetract.setVisibility(
                    open && !poll.getMyOptionIds().isEmpty() ? View.VISIBLE : View.GONE);
            binding.pollRetract.setEnabled(!row.voting);
            binding.pollRetract.setOnClickListener(ignored -> listener.onRetract(poll.getId()));
        }

        private void addOption(
                Context context,
                Poll poll,
                PollOption option,
                Row row,
                boolean closed,
                int maxCount,
                Listener listener) {
            boolean checked = row.selection.contains(option.getId());
            String count = com.nexachat.res.QuantityStrings.pollOptionVoteCount(
                    context.getResources(), option.getVoteCount());
            String names = voterNames(poll, option, row.voterNames);
            String result = names.isEmpty()
                    ? count
                    : context.getString(R.string.poll_option_count_named, count, names);
            int percent = maxCount == 0 ? 0 : Math.round(option.getVoteCount() * 100f / maxCount);
            if (poll.isMultipleChoice()) {
                ItemPollOptionMultipleBinding optionBinding =
                        ItemPollOptionMultipleBinding.inflate(
                                LayoutInflater.from(context), binding.pollOptions, true);
                bindControl(optionBinding.optionControl, optionBinding.optionProgress,
                        optionBinding.optionCount, option, checked, result, percent,
                        closed || row.voting, poll.getId(), listener);
            } else {
                ItemPollOptionSingleBinding optionBinding =
                        ItemPollOptionSingleBinding.inflate(
                                LayoutInflater.from(context), binding.pollOptions, true);
                bindControl(optionBinding.optionControl, optionBinding.optionProgress,
                        optionBinding.optionCount, option, checked, result, percent,
                        closed || row.voting, poll.getId(), listener);
            }
        }

        private void bindControl(
                CompoundButton control,
                ProgressBar progress,
                TextView countView,
                PollOption option,
                boolean checked,
                String result,
                int percent,
                boolean disabled,
                String pollId,
                Listener listener) {
            control.setText(option.getLabel());
            control.setChecked(checked);
            control.setEnabled(!disabled);
            control.setOnClickListener(ignored -> listener.onToggle(pollId, option.getId()));
            progress.setProgress(percent);
            countView.setText(result);
            countView.setMaxLines(3);
        }

        private static String voterNames(
                Poll poll, PollOption option, Map<String, String> names) {
            if (poll.isAnonymous() || option.getVoterIds().isEmpty()) {
                return "";
            }
            List<String> labels = new ArrayList<>();
            for (String voterId : option.getVoterIds()) {
                String name = names.get(voterId);
                labels.add(name == null || name.isBlank() ? voterId : name);
            }
            return String.join(", ", labels);
        }

        private static String meta(Context context, Poll poll, boolean closed) {
            List<String> parts = new ArrayList<>();
            parts.add(context.getString(closed ? R.string.poll_closed : R.string.poll_open));
            if (poll.isMultipleChoice()) {
                parts.add(context.getString(R.string.poll_multiple));
            }
            if (poll.isAnonymous()) {
                parts.add(context.getString(R.string.poll_anonymous));
            }
            if (poll.getClosedAt() != null) {
                parts.add(context.getString(R.string.poll_closed_on,
                        PollText.date(context, poll.getClosedAt())));
            } else if (poll.getClosesAt() != null) {
                parts.add(context.getString(R.string.poll_closes,
                        PollText.date(context, poll.getClosesAt())));
            }
            return String.join(context.getString(R.string.meta_separator), parts);
        }

        private static boolean sameSelection(List<String> left, List<String> right) {
            Set<String> leftSet = new HashSet<>(left);
            Set<String> rightSet = new HashSet<>(right);
            return leftSet.equals(rightSet);
        }
    }
}

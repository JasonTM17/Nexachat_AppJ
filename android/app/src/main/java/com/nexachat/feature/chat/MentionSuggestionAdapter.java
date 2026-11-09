package com.nexachat.feature.chat;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Small inline picker. Every row meets the app's 48dp touch-target minimum. */
final class MentionSuggestionAdapter extends RecyclerView.Adapter<MentionSuggestionAdapter.Holder> {
    private final List<MentionPickerRules.Suggestion> items = new ArrayList<>();
    private final Consumer<MentionPickerRules.Suggestion> onSelected;

    MentionSuggestionAdapter(Consumer<MentionPickerRules.Suggestion> onSelected) {
        this.onSelected = onSelected;
        setHasStableIds(false);
    }

    void submit(List<MentionPickerRules.Suggestion> suggestions) {
        items.clear();
        if (suggestions != null) items.addAll(suggestions);
        notifyDataSetChanged();
    }

    boolean focusFirst() {
        if (items.isEmpty()) return false;
        RecyclerView recycler = currentRecycler;
        if (recycler == null) return false;
        recycler.scrollToPosition(0);
        recycler.post(() -> {
            RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
        });
        return true;
    }

    private RecyclerView currentRecycler;

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView row = new TextView(parent.getContext());
        float density = parent.getResources().getDisplayMetrics().density;
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48f * density)));
        row.setMinHeight(Math.round(48f * density));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(16f * density), 0, Math.round(16f * density), 0);
        row.setMaxLines(1);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
        row.setFocusable(true);
        row.setClickable(true);
        android.util.TypedValue background = new android.util.TypedValue();
        if (row.getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, background, true)) {
            row.setBackgroundResource(background.resourceId);
        }
        return new Holder(row);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        MentionPickerRules.Suggestion suggestion = items.get(position);
        TextView row = (TextView) holder.itemView;
        if (suggestion.isAll()) {
            row.setText(R.string.mention_all_picker);
            row.setContentDescription(row.getContext().getString(R.string.mention_all_picker));
        } else {
            String name = suggestion.getDisplayName() == null ? "" : suggestion.getDisplayName();
            row.setText(name);
            row.setContentDescription(row.getContext().getString(R.string.mention_person_picker, name));
        }
        row.setOnClickListener(view -> onSelected.accept(suggestion));
        row.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP
                    && (keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER)) {
                onSelected.accept(suggestion);
                return true;
            }
            return false;
        });
    }

    @Override public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        currentRecycler = recyclerView;
    }

    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        if (currentRecycler == recyclerView) currentRecycler = null;
        super.onDetachedFromRecyclerView(recyclerView);
    }

    @Override public int getItemCount() { return Math.min(items.size(), 5); }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(@NonNull TextView itemView) { super(itemView); }
    }
}

package com.nexachat.feature.group.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.Contact;
import com.nexachat.feature.group.GroupContactPickerSupport;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Contact rows with non-interactive checkmarks driven by row activation. */
public final class SelectableContactAdapter extends ListAdapter<Contact, SelectableContactAdapter.Holder> {

    public interface ToggleListener {
        void onToggle(@NonNull String userId);
    }

    private final ToggleListener listener;
    private Set<String> selectedIds = Set.of();

    public SelectableContactAdapter(@NonNull ToggleListener listener) {
        super(DIFF);
        this.listener = listener;
    }

    public void submit(@NonNull List<Contact> contacts, @NonNull Set<String> selected) {
        Set<String> previous = selectedIds;
        selectedIds = selected;
        List<Contact> before = getCurrentList();
        submitList(contacts);
        if (GroupContactPickerSupport.sameContactSnapshot(before, contacts)) {
            GroupContactPickerSupport.notifySelectionRebinds(this, contacts, previous, selected);
        }
    }

    private static final DiffUtil.ItemCallback<Contact> DIFF = new DiffUtil.ItemCallback<Contact>() {
        @Override
        public boolean areItemsTheSame(@NonNull Contact oldItem, @NonNull Contact newItem) {
            return oldItem.getUserId().equals(newItem.getUserId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull Contact oldItem, @NonNull Contact newItem) {
            return Objects.equals(oldItem, newItem);
        }
    };

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_user_search, parent, false);
        CheckBox checkBox = row.findViewById(R.id.selectionCheck);
        checkBox.setVisibility(View.VISIBLE);
        return new Holder(row, checkBox);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Contact contact = getItem(position);
        holder.bind(contact, selectedIds.contains(contact.getUserId()), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains(GroupContactPickerSupport.PAYLOAD_SELECTION)) {
            Contact contact = getItem(position);
            holder.bindSelection(
                    contact,
                    selectedIds.contains(contact.getUserId()),
                    contact.getPrimaryLabel());
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final AvatarView avatar;
        private final TextView name;
        private final TextView subtitle;
        private final CheckBox checkBox;

        Holder(@NonNull View itemView, @NonNull CheckBox checkBox) {
            super(itemView);
            this.checkBox = checkBox;
            avatar = itemView.findViewById(R.id.avatar);
            name = itemView.findViewById(R.id.name);
            subtitle = itemView.findViewById(R.id.subtitle);
        }

        void bind(@NonNull Contact contact, boolean selected, @NonNull ToggleListener listener) {
            String label = contact.getPrimaryLabel();
            name.setText(label);
            String line = com.nexachat.core.common.PersonContactSubtitle.subtitle(contact);
            if (line == null || line.isBlank()) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setText(line);
            }
            avatar.setName(label);
            avatar.setAvatarUrl(contact.getAvatarUrl());
            bindSelection(contact, selected, label);
            View.OnClickListener toggle = v -> listener.onToggle(contact.getUserId());
            itemView.setOnClickListener(toggle);
            checkBox.setOnCheckedChangeListener((b, checked) -> listener.onToggle(contact.getUserId()));
        }

        void bindSelection(@NonNull Contact contact, boolean selected, @NonNull String personLabel) {
            GroupContactPickerSupport.applySelectionPresentation(itemView, checkBox, selected, personLabel);
        }
    }
}

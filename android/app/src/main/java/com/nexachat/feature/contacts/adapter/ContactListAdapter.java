package com.nexachat.feature.contacts.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.core.common.PersonContactSubtitle;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.Contact;
import com.nexachat.feature.contacts.ContactSections;
import java.util.List;

/**
 * Contact rows grouped under initial-letter badge headers. Each section forms
 * one rounded tonal band built from the first/middle/last row backgrounds.
 */
public final class ContactListAdapter extends ListAdapter<ContactSections.Row, RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CONTACT = 1;

    public interface Listener {
        void onContactClick(@NonNull String userId);
    }

    private final Listener listener;

    public ContactListAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    /** Convenience: expands the flat, sorted contact list into sectioned rows. */
    public void submitContacts(@NonNull List<Contact> contacts) {
        submitList(ContactSections.group(contacts));
    }

    /** Section letter governing {@code position}; null when out of range. */
    @Nullable
    public String letterForPosition(int position) {
        if (position < 0 || position >= getItemCount()) {
            return null;
        }
        for (int i = position; i >= 0; i--) {
            ContactSections.Row row = getItem(i);
            if (row instanceof ContactSections.Row.Header header) {
                return header.getLetter();
            }
        }
        return null;
    }

    public boolean isHeaderPosition(int position) {
        return position >= 0
                && position < getItemCount()
                && getItem(position) instanceof ContactSections.Row.Header;
    }

    private static final DiffUtil.ItemCallback<ContactSections.Row> DIFF =
            new DiffUtil.ItemCallback<ContactSections.Row>() {
        @Override
        public boolean areItemsTheSame(
                @NonNull ContactSections.Row oldItem, @NonNull ContactSections.Row newItem) {
            if (oldItem instanceof ContactSections.Row.Header oldHeader
                    && newItem instanceof ContactSections.Row.Header newHeader) {
                return oldHeader.getLetter().equals(newHeader.getLetter());
            }
            if (oldItem instanceof ContactSections.Row.Item oldRow
                    && newItem instanceof ContactSections.Row.Item newRow) {
                return oldRow.getContact().getUserId().equals(newRow.getContact().getUserId());
            }
            return false;
        }

        @Override
        public boolean areContentsTheSame(
                @NonNull ContactSections.Row oldItem, @NonNull ContactSections.Row newItem) {
            if (oldItem instanceof ContactSections.Row.Header oldHeader
                    && newItem instanceof ContactSections.Row.Header newHeader) {
                return oldHeader.equals(newHeader);
            }
            if (oldItem instanceof ContactSections.Row.Item oldRow
                    && newItem instanceof ContactSections.Row.Item newRow) {
                return oldRow.equals(newRow);
            }
            return false;
        }
    };

    @Override
    public int getItemViewType(int position) {
        return getItem(position) instanceof ContactSections.Row.Header ? TYPE_HEADER : TYPE_CONTACT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_contact_section, parent, false));
        }
        return new Holder(inflater.inflate(R.layout.item_contact, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ContactSections.Row row = getItem(position);
        if (holder instanceof HeaderHolder headerHolder) {
            headerHolder.letter.setText(((ContactSections.Row.Header) row).getLetter());
            return;
        }
        ((Holder) holder).bind((ContactSections.Row.Item) row, listener);
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        private final TextView letter;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            letter = itemView.findViewById(R.id.sectionLetter);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final AvatarView avatar;
        private final TextView name;
        private final TextView phone;
        private final View divider;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            name = itemView.findViewById(R.id.name);
            phone = itemView.findViewById(R.id.phone);
            divider = itemView.findViewById(R.id.rowDivider);
        }

        void bind(@NonNull ContactSections.Row.Item row, @NonNull Listener listener) {
            Contact contact = row.getContact();
            String label = contact.getPrimaryLabel();
            name.setText(label);
            float fontScale = itemView.getResources().getConfiguration().fontScale;
            boolean largeType = fontScale >= 1.3f;
            name.setMaxLines(largeType ? 2 : 1);
            name.setEllipsize(largeType ? null : android.text.TextUtils.TruncateAt.END);
            if (phone != null) {
                phone.setMaxLines(largeType ? 2 : 1);
                phone.setEllipsize(largeType ? null : android.text.TextUtils.TruncateAt.END);
                String subtitle = secondaryLine(contact);
                if (subtitle != null && !subtitle.isBlank()) {
                    phone.setText(subtitle);
                    phone.setVisibility(View.VISIBLE);
                } else {
                    phone.setVisibility(View.GONE);
                }
            }
            boolean single = row.isFirstInSection() && row.isLastInSection();
            itemView.setBackgroundResource(single
                    ? R.drawable.bg_grouped_list_band
                    : row.isFirstInSection()
                            ? R.drawable.bg_grouped_list_band_top
                            : row.isLastInSection()
                                    ? R.drawable.bg_grouped_list_band_bottom
                                    : R.drawable.bg_grouped_list_band_mid);
            divider.setVisibility(row.isLastInSection() ? View.GONE : View.VISIBLE);
            avatar.setName(label);
            avatar.setAvatarUrl(contact.getAvatarUrl());
            avatar.setOnlineStatus(null);
            itemView.setOnClickListener(v -> listener.onContactClick(contact.getUserId()));
        }

        /**
         * Secondary line from data the model already carries and the detail
         * sheet already shows: the phone when present, else the verified email.
         */
        @Nullable
        private static String secondaryLine(@NonNull Contact contact) {
            String contactPhone = contact.getPhone();
            if (contactPhone != null && !contactPhone.isBlank()) {
                return contactPhone.trim();
            }
            return PersonContactSubtitle.subtitle(contact);
        }
    }
}

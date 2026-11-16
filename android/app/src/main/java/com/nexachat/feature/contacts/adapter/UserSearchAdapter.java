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
import com.nexachat.core.network.dto.UserDto;

/**
 * User discovery search result rows.
 */
public final class UserSearchAdapter extends ListAdapter<UserDto, UserSearchAdapter.Holder> {

    public interface Listener {
        void onUserClick(@NonNull String userId);
    }

    private final Listener listener;

    public UserSearchAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<UserDto> DIFF = new DiffUtil.ItemCallback<UserDto>() {
        @Override
        public boolean areItemsTheSame(@NonNull UserDto oldItem, @NonNull UserDto newItem) {
            return oldItem.getId().equals(newItem.getId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull UserDto oldItem, @NonNull UserDto newItem) {
            return oldItem.equals(newItem);
        }
    };

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_user_search, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position), position == getItemCount() - 1, listener);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final AvatarView avatar;
        private final TextView name;
        private final TextView subtitle;
        private final View divider;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            name = itemView.findViewById(R.id.name);
            subtitle = itemView.findViewById(R.id.subtitle);
            divider = itemView.findViewById(R.id.rowDivider);
        }

        void bind(@NonNull UserDto user, boolean isLast, @NonNull Listener listener) {
            name.setText(user.getDisplayName());
            float fontScale = itemView.getResources().getConfiguration().fontScale;
            boolean largeType = fontScale >= 1.3f;
            name.setMaxLines(largeType ? 2 : 1);
            name.setEllipsize(largeType ? null : android.text.TextUtils.TruncateAt.END);
            subtitle.setMaxLines(largeType ? 2 : 1);
            subtitle.setEllipsize(largeType ? null : android.text.TextUtils.TruncateAt.END);
            String line = secondaryLine(user);
            if (line == null || line.isBlank()) {
                subtitle.setVisibility(android.view.View.GONE);
            } else {
                subtitle.setVisibility(android.view.View.VISIBLE);
                subtitle.setText(line);
            }
            divider.setVisibility(isLast ? android.view.View.GONE : android.view.View.VISIBLE);
            avatar.setName(user.getDisplayName());
            avatar.setAvatarUrl(user.getAvatarUrl());
            itemView.setOnClickListener(v -> listener.onUserClick(user.getId()));
        }

        @Nullable
        private static String secondaryLine(@NonNull UserDto user) {
            String phone = user.getPhone();
            if (phone != null && !phone.isBlank()) {
                return phone.trim();
            }
            return PersonContactSubtitle.subtitle(user);
        }
    }
}

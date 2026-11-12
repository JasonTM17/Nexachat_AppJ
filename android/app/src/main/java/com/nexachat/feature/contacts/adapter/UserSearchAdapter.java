package com.nexachat.feature.contacts.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.core.common.PersonContactSubtitle;
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
        holder.bind(getItem(position), listener);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final AvatarView avatar;
        private final TextView name;
        private final TextView subtitle;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            name = itemView.findViewById(R.id.name);
            subtitle = itemView.findViewById(R.id.subtitle);
        }

        void bind(@NonNull UserDto user, @NonNull Listener listener) {
            name.setText(user.getDisplayName());
            String line = PersonContactSubtitle.subtitle(user);
            if (line == null || line.isBlank()) {
                subtitle.setVisibility(android.view.View.GONE);
            } else {
                subtitle.setVisibility(android.view.View.VISIBLE);
                subtitle.setText(line);
            }
            avatar.setName(user.getDisplayName());
            avatar.setAvatarUrl(user.getAvatarUrl());
            itemView.setOnClickListener(v -> listener.onUserClick(user.getId()));
        }
    }
}

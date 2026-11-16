package com.nexachat.feature.safety;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import java.util.ArrayList;
import java.util.List;

/** Blocked-user rows with an inline unblock action. */
public final class BlockedUserAdapter extends RecyclerView.Adapter<BlockedUserAdapter.Holder> {

    public interface Listener {
        void onUnblock(@NonNull SafetyRepository.BlockedUser user);
    }

    private final Listener listener;
    private final List<SafetyRepository.BlockedUser> items = new ArrayList<>();

    public BlockedUserAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<SafetyRepository.BlockedUser> rows) {
        items.clear();
        items.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_blocked_user, parent, false);
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SafetyRepository.BlockedUser user = items.get(position);
        String name = user.displayName() == null || user.displayName().isBlank()
                ? (user.phone() == null ? "" : user.phone())
                : user.displayName();
        holder.name.setText(name);
        holder.avatar.setName(name);
        holder.avatar.setAvatarUrl(user.avatarUrl());
        holder.avatar.setOnlineStatus(null);
        boolean showPhone = user.phone() != null
                && !user.phone().isBlank()
                && !user.phone().equals(name);
        holder.phone.setVisibility(showPhone ? View.VISIBLE : View.GONE);
        if (showPhone) {
            holder.phone.setText(user.phone());
        }
        holder.unblock.setOnClickListener(ignored -> listener.onUnblock(user));
    }

    @Override public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final AvatarView avatar;
        final TextView name;
        final TextView phone;
        final MaterialButton unblock;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.blockedAvatar);
            name = itemView.findViewById(R.id.blockedName);
            phone = itemView.findViewById(R.id.blockedPhone);
            unblock = itemView.findViewById(R.id.unblockButton);
        }
    }
}

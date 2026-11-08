package com.nexachat.feature.group.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.GroupMember;

/**
 * Group detail member list with optional remove affordance.
 */
public final class GroupMemberAdapter extends ListAdapter<GroupMember, GroupMemberAdapter.Holder> {

    public interface Listener {
        void onMemberClick(@NonNull String userId);

        void onRemoveMember(@NonNull String userId);
    }

    private final Listener listener;
    private boolean showRemove;

    public GroupMemberAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    public void setShowRemove(boolean showRemove) {
        this.showRemove = showRemove;
    }

    private static final DiffUtil.ItemCallback<GroupMember> DIFF = new DiffUtil.ItemCallback<GroupMember>() {
        @Override
        public boolean areItemsTheSame(@NonNull GroupMember oldItem, @NonNull GroupMember newItem) {
            return oldItem.getUserId().equals(newItem.getUserId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull GroupMember oldItem, @NonNull GroupMember newItem) {
            return oldItem.equals(newItem);
        }
    };

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group_member, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position), listener, showRemove);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final AvatarView avatar;
        private final TextView memberName;
        private final Chip memberRole;
        private final ImageButton removeMember;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            memberName = itemView.findViewById(R.id.memberName);
            memberRole = itemView.findViewById(R.id.memberRole);
            removeMember = itemView.findViewById(R.id.removeMember);
        }

        void bind(@NonNull GroupMember member, @NonNull Listener listener, boolean showRemove) {
            memberName.setText(member.getDisplayName());
            avatar.setName(member.getDisplayName());
            avatar.setAvatarUrl(member.getAvatarUrl());
            memberRole.setVisibility(member.isAdmin() ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(v -> listener.onMemberClick(member.getUserId()));
            removeMember.setVisibility(showRemove && !member.isAdmin() ? View.VISIBLE : View.GONE);
            removeMember.setOnClickListener(v -> listener.onRemoveMember(member.getUserId()));
        }
    }
}

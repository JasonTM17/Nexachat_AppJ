package com.nexachat.feature.invite;

import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Objects;

/** Accessible pending-request rows with approve and reject actions. */
final class InviteRequestAdapter
        extends ListAdapter<JoinRequestResponse, InviteRequestAdapter.Holder> {
    interface Listener {
        void onReview(@NonNull String requestId, boolean approve);
    }

    private final Listener listener;
    private boolean actionsEnabled = true;

    InviteRequestAdapter(Listener listener) {
        super(new DiffUtil.ItemCallback<>() {
            @Override
            public boolean areItemsTheSame(
                    @NonNull JoinRequestResponse oldItem,
                    @NonNull JoinRequestResponse newItem) {
                return oldItem.id != null && oldItem.id.equals(newItem.id);
            }

            @Override
            public boolean areContentsTheSame(
                    @NonNull JoinRequestResponse oldItem,
                    @NonNull JoinRequestResponse newItem) {
                return Objects.equals(oldItem.status, newItem.status)
                        && Objects.equals(oldItem.createdAt, newItem.createdAt);
            }
        });
        this.listener = listener;
    }

    void setActionsEnabled(boolean enabled) {
        actionsEnabled = enabled;
        notifyItemRangeChanged(0, getItemCount());
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_invite_join_request, parent, false);
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        JoinRequestResponse request = getItem(position);
        String name = request.user == null || request.user.displayName == null
                ? "" : request.user.displayName;
        holder.avatar.setName(name);
        holder.avatar.setAvatarUrl(request.user == null ? null : request.user.avatarUrl);
        holder.name.setText(name);
        holder.time.setText(holder.itemView.getContext().getString(
                R.string.invite_request_time, formatDate(holder, request.createdAt)));
        holder.approve.setEnabled(actionsEnabled);
        holder.reject.setEnabled(actionsEnabled);
        holder.approve.setOnClickListener(v -> {
            if (request.id != null) listener.onReview(request.id, true);
        });
        holder.reject.setOnClickListener(v -> {
            if (request.id != null) listener.onReview(request.id, false);
        });
    }

    private static String formatDate(Holder holder, String raw) {
        if (raw == null) return "";
        try {
            Date date = Date.from(Instant.parse(raw));
            return DateFormat.getMediumDateFormat(holder.itemView.getContext()).format(date)
                    + " " + DateFormat.getTimeFormat(holder.itemView.getContext()).format(date);
        } catch (DateTimeParseException invalid) {
            return "";
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final AvatarView avatar;
        final TextView name;
        final TextView time;
        final MaterialButton approve;
        final MaterialButton reject;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.requestAvatar);
            name = itemView.findViewById(R.id.requestName);
            time = itemView.findViewById(R.id.requestTime);
            approve = itemView.findViewById(R.id.approveButton);
            reject = itemView.findViewById(R.id.rejectButton);
        }
    }
}

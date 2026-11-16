package com.nexachat.feature.safety;

import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.feature.chat.SharedContentUi;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Starred-message rows: sender, snippet, star time; tap jumps to the message. */
public final class StarredMessageAdapter
        extends RecyclerView.Adapter<StarredMessageAdapter.Holder> {

    public interface Listener {
        void onOpen(@NonNull SafetyRepository.StarredMessage row);
    }

    private final Listener listener;
    private final List<SafetyRepository.StarredMessage> items = new ArrayList<>();

    public StarredMessageAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<SafetyRepository.StarredMessage> rows) {
        items.clear();
        items.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_starred_message, parent, false);
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SafetyRepository.StarredMessage row = items.get(position);
        MessageDto message = row.message();
        String sender = message.getSenderDisplayName();
        holder.sender.setText(sender == null || sender.isBlank()
                ? holder.itemView.getContext().getString(R.string.archive_unknown_sender)
                : sender);
        String content = message.getContent();
        holder.snippet.setText(content == null || content.isBlank()
                ? holder.itemView.getContext().getString(R.string.inbox_preview_file)
                : SharedContentUi.previewText(holder.itemView.getContext(), content));
        holder.time.setText(formatTime(holder, row.starredAt()));
        holder.itemView.setOnClickListener(ignored -> listener.onOpen(row));
    }

    @NonNull
    private static String formatTime(@NonNull Holder holder, @Nullable String starredAt) {
        if (starredAt == null) {
            return "";
        }
        try {
            return DateFormat.getDateFormat(holder.itemView.getContext())
                    .format(java.util.Date.from(Instant.parse(starredAt)));
        } catch (DateTimeParseException ignored) {
            return "";
        }
    }

    @Override public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView sender;
        final TextView snippet;
        final TextView time;

        Holder(@NonNull View itemView) {
            super(itemView);
            sender = itemView.findViewById(R.id.starredSender);
            snippet = itemView.findViewById(R.id.starredSnippet);
            time = itemView.findViewById(R.id.starredTime);
        }
    }
}

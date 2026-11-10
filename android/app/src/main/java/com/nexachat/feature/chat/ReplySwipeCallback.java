package com.nexachat.feature.chat;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import com.nexachat.R;
import com.nexachat.data.ChatMessage;
import com.nexachat.feature.chat.adapter.MessageListAdapter;
import com.nexachat.feature.chat.adapter.MessageListItem;
import com.nexachat.feature.chat.adapter.MessageViewTypeMapper;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A directional shortcut to the existing reply action. The row is never removed. */
final class ReplySwipeCallback extends ItemTouchHelper.SimpleCallback {
    private final MessageListAdapter adapter;
    private final BooleanSupplier disabled;
    private final Consumer<ChatMessage> onReply;

    ReplySwipeCallback(MessageListAdapter adapter, BooleanSupplier disabled, Consumer<ChatMessage> onReply) {
        super(0, ItemTouchHelper.END);
        this.adapter = adapter;
        this.disabled = disabled;
        this.onReply = onReply;
    }

    @Override public int getMovementFlags(@NonNull RecyclerView list, @NonNull RecyclerView.ViewHolder holder) {
        ChatMessage message = messageAt(holder.getBindingAdapterPosition());
        boolean replyable = !disabled.getAsBoolean() && message != null && !message.isDeleted()
                && ChatBubbleRules.isMessageReplyable(message.getStatus())
                && !MessageViewTypeMapper.isSystemMessage(message);
        return makeMovementFlags(0, replyable ? ItemTouchHelper.END : 0);
    }

    @Override public boolean onMove(@NonNull RecyclerView list, @NonNull RecyclerView.ViewHolder from,
            @NonNull RecyclerView.ViewHolder to) { return false; }

    @Override public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder holder) { return 0.25f; }
    @Override public float getSwipeEscapeVelocity(float velocity) { return Float.MAX_VALUE; }

    @Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
        int position = holder.getBindingAdapterPosition();
        ChatMessage message = messageAt(position);
        if (position != RecyclerView.NO_POSITION) adapter.notifyItemChanged(position);
        if (!disabled.getAsBoolean() && message != null && !message.isDeleted()
                && ChatBubbleRules.isMessageReplyable(message.getStatus())) onReply.accept(message);
    }

    @Override public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView list,
            @NonNull RecyclerView.ViewHolder holder, float dx, float dy, int state, boolean active) {
        View row = holder.itemView;
        int size = row.getResources().getDimensionPixelSize(R.dimen.space_lg);
        int inset = row.getResources().getDimensionPixelSize(R.dimen.space_md);
        float offset = Math.copySign(Math.min(Math.abs(dx), size + 2f * inset), dx);
        if (dx != 0) {
            Drawable icon = AppCompatResources.getDrawable(row.getContext(), R.drawable.ic_reply);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimary));
                int left = dx > 0 ? row.getLeft() + inset : row.getRight() - inset - size;
                int top = row.getTop() + (row.getHeight() - size) / 2;
                icon.setBounds(left, top, left + size, top + size);
                icon.setAlpha((int) (255 * Math.min(1f, Math.abs(offset) / (size + inset))));
                icon.draw(canvas);
            }
        }
        super.onChildDraw(canvas, list, holder, offset, 0, state, active);
    }

    private ChatMessage messageAt(int position) {
        if (position < 0 || position >= adapter.getCurrentList().size()) return null;
        MessageListItem item = adapter.getCurrentList().get(position);
        return item.getMessage();
    }
}

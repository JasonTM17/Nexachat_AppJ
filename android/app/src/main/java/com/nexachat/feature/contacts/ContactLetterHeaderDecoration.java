package com.nexachat.feature.contacts;

import android.graphics.Canvas;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.feature.contacts.adapter.ContactListAdapter;

/**
 * Draws the current section's letter badge pinned to the top of the list while
 * its rows are on screen, sliding up when the next badge pushes in.
 */
final class ContactLetterHeaderDecoration extends RecyclerView.ItemDecoration {

    private final ContactListAdapter adapter;
    private String boundLetter;
    private View pinnedHeader;
    private TextView pinnedLetter;

    ContactLetterHeaderDecoration(@NonNull ContactListAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void onDrawOver(@NonNull Canvas canvas,
            @NonNull RecyclerView parent,
            @NonNull RecyclerView.State state) {
        RecyclerView.LayoutManager layoutManager = parent.getLayoutManager();
        if (!(layoutManager instanceof LinearLayoutManager)) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) layoutManager;
        int firstVisible = lm.findFirstVisibleItemPosition();
        if (firstVisible == RecyclerView.NO_POSITION) {
            return;
        }
        String letter = adapter.letterForPosition(firstVisible);
        if (letter == null) {
            return;
        }
        ensurePinned(parent, letter);
        canvas.save();
        canvas.translate(0f, pinnedOffset(parent));
        pinnedHeader.draw(canvas);
        canvas.restore();
    }

    private int pinnedOffset(@NonNull RecyclerView parent) {
        int pinnedHeight = pinnedHeader.getHeight();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position != RecyclerView.NO_POSITION && adapter.isHeaderPosition(position)) {
                int top = child.getTop();
                if (top < pinnedHeight) {
                    if (top > 0) {
                        return top - pinnedHeight;
                    }
                } else {
                    return 0;
                }
            }
        }
        return 0;
    }

    private void ensurePinned(@NonNull RecyclerView parent, @NonNull String letter) {
        if (pinnedHeader == null) {
            pinnedHeader = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_contact_section, parent, false);
            pinnedLetter = pinnedHeader.findViewById(R.id.sectionLetter);
        }
        if (!letter.equals(boundLetter) || pinnedHeader.getMeasuredWidth() != parent.getWidth()) {
            boundLetter = letter;
            pinnedLetter.setText(letter);
            pinnedHeader.measure(
                    View.MeasureSpec.makeMeasureSpec(parent.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            pinnedHeader.layout(0, 0, pinnedHeader.getMeasuredWidth(), pinnedHeader.getMeasuredHeight());
        }
    }
}

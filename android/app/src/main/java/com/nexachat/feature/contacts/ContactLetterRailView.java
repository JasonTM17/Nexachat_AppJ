package com.nexachat.feature.contacts;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.color.MaterialColors;
import com.nexachat.R;
import java.util.ArrayList;
import java.util.List;

/**
 * Vertical A–Z rail beside the contact list. One cell per existing section
 * letter; tapping or dragging scrubs the list to that section. Each cell is
 * 48dp wide with its own content description, so screen-reader and keyboard
 * users get the same jump without the swipe gesture.
 */
public final class ContactLetterRailView extends LinearLayout {

    public interface Listener {
        /** A letter became active (drag pass, tap, or accessibility activation). */
        void onLetterActivated(@NonNull String letter, float anchorY);

        /** The scrub gesture ended; the preview bubble can fade. */
        void onScrubEnd();
    }

    private final List<TextView> cells = new ArrayList<>();
    private List<String> letters = List.of();
    @Nullable private Listener listener;
    @Nullable private String activeLetter;
    private int letterColor;
    private int activeColor;

    public ContactLetterRailView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        letterColor = MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        activeColor = MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorPrimary);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Rebuilds the cells when the section letters changed. */
    public void setLetters(@NonNull List<String> next) {
        if (next.equals(letters)) {
            return;
        }
        letters = new ArrayList<>(next);
        activeLetter = null;
        removeAllViews();
        cells.clear();
        for (String letter : letters) {
            TextView cell = new TextView(getContext());
            cell.setText(letter);
            TextViewCompat.setTextAppearance(cell, R.style.TextAppearance_NexaChat_LabelSmall);
            cell.setTextColor(letterColor);
            cell.setGravity(Gravity.CENTER);
            cell.setContentDescription("#".equals(letter)
                    ? getContext().getString(R.string.a11y_contacts_section_other)
                    : getContext().getString(R.string.a11y_contacts_section_jump, letter));
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setOnClickListener(this::onCellClick);
            addView(cell, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
            cells.add(cell);
        }
    }

    private void onCellClick(@NonNull View cell) {
        int index = cells.indexOf(cell);
        if (index >= 0) {
            activateIndex(index);
            endScrub();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return !letters.isEmpty() || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (letters.isEmpty()) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                activateIndex(indexAt(event.getY()));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endScrub();
                return true;
            default:
                return true;
        }
    }

    private int indexAt(float y) {
        float inner = getHeight() - getPaddingTop() - getPaddingBottom();
        float fraction = inner > 0 ? (y - getPaddingTop()) / inner : 0f;
        return ContactLetterIndex.indexAtFraction(cells.size(), fraction);
    }

    private void activateIndex(int index) {
        if (index < 0 || index >= letters.size()) {
            return;
        }
        String letter = letters.get(index);
        if (letter.equals(activeLetter)) {
            return;
        }
        activeLetter = letter;
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setTextColor(i == index ? activeColor : letterColor);
        }
        if (listener != null) {
            TextView cell = cells.get(index);
            listener.onLetterActivated(letter, cell.getTop() + cell.getHeight() / 2f);
        }
    }

    private void endScrub() {
        activeLetter = null;
        for (TextView cell : cells) {
            cell.setTextColor(letterColor);
        }
        if (listener != null) {
            listener.onScrubEnd();
        }
    }
}

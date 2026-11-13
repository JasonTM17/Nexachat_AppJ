package com.nexachat.feature.group;

import android.view.View;
import android.widget.CheckBox;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.data.Contact;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Group contact pickers keep checkbox visuals in sync when only selection changes.
 */
public final class GroupContactPickerSupport {

    public static final Object PAYLOAD_SELECTION = new Object();

    private GroupContactPickerSupport() {}

    @NonNull
    public static List<Integer> selectionRebindIndices(
            @NonNull List<Contact> contacts,
            @NonNull Set<String> before,
            @NonNull Set<String> after) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < contacts.size(); i++) {
            String id = contacts.get(i).getUserId();
            if (before.contains(id) != after.contains(id)) {
                indices.add(i);
            }
        }
        return indices;
    }

    public static boolean sameContactSnapshot(@NonNull List<Contact> left, @NonNull List<Contact> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).equals(right.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static void notifySelectionRebinds(
            @NonNull RecyclerView.Adapter<?> adapter,
            @NonNull List<Contact> contacts,
            @NonNull Set<String> before,
            @NonNull Set<String> after) {
        for (int index : selectionRebindIndices(contacts, before, after)) {
            adapter.notifyItemChanged(index, PAYLOAD_SELECTION);
        }
    }

    public static void applySelectionPresentation(
            @NonNull View row,
            @NonNull CheckBox checkBox,
            boolean selected,
            @NonNull String personLabel) {
        checkBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        checkBox.setOnCheckedChangeListener(null);
        checkBox.setChecked(selected);

        View focus = row.findViewById(R.id.rowContent);
        if (focus == null) {
            focus = row;
        }
        ViewCompat.setAccessibilityDelegate(focus, new androidx.core.view.AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    @NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(personLabel);
                info.setCheckable(true);
                info.setChecked(selected);
            }
        });
    }
}

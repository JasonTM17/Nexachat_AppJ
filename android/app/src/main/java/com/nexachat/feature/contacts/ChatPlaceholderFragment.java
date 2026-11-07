package com.nexachat.feature.contacts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.nexachat.R;

/** Temporary empty destination retained for hosts that have not migrated to the chat fragment. */
public final class ChatPlaceholderFragment extends Fragment {
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        TextView label = new TextView(requireContext());
        String conversationId = getArguments() == null ? "" : getArguments().getString("conversationId", "");
        label.setText(getString(R.string.chat_placeholder, conversationId));
        label.setGravity(android.view.Gravity.CENTER);
        return label;
    }
}

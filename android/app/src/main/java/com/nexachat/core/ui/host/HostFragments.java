package com.nexachat.core.ui.host;

import com.nexachat.R;

/** Nav graph fragment shells (one class per destination for Navigation Component). */
public final class HostFragments {

    private HostFragments() {
    }

    /**
     * Placeholder chat destination until {@link com.nexachat.feature.chat.ChatFragment}
     * is wired on the nav graph (luna9).
     */
    public static final class ChatHostFragment extends LayoutHostFragment {
        public ChatHostFragment() {
            super(R.layout.fragment_chat);
        }
    }
}

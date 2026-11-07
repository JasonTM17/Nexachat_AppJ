package com.nexachat.core.ui.host;

import com.nexachat.R;

/** Nav graph fragment shells (one class per destination for Navigation Component). */
public final class HostFragments {

    private HostFragments() {
    }

    public static final class SplashHostFragment extends LayoutHostFragment {
        public SplashHostFragment() {
            super(R.layout.fragment_splash);
        }
    }

    public static final class LoginHostFragment extends LayoutHostFragment {
        public LoginHostFragment() {
            super(R.layout.fragment_login);
        }
    }

    public static final class RegisterHostFragment extends LayoutHostFragment {
        public RegisterHostFragment() {
            super(R.layout.fragment_register);
        }
    }

    public static final class InboxHostFragment extends LayoutHostFragment {
        public InboxHostFragment() {
            super(R.layout.fragment_inbox);
        }
    }

    public static final class ContactsHostFragment extends LayoutHostFragment {
        public ContactsHostFragment() {
            super(R.layout.fragment_contacts);
        }
    }

    public static final class SearchHostFragment extends LayoutHostFragment {
        public SearchHostFragment() {
            super(R.layout.fragment_user_search);
        }
    }

    public static final class ProfileHostFragment extends LayoutHostFragment {
        public ProfileHostFragment() {
            super(R.layout.fragment_profile);
        }
    }

    public static final class LanguageSettingsHostFragment extends LayoutHostFragment {
        public LanguageSettingsHostFragment() {
            super(R.layout.fragment_language_settings);
        }
    }

    public static final class ChatHostFragment extends LayoutHostFragment {
        public ChatHostFragment() {
            super(R.layout.fragment_chat);
        }
    }

    public static final class GroupCreateHostFragment extends LayoutHostFragment {
        public GroupCreateHostFragment() {
            super(R.layout.fragment_create_group);
        }
    }

    public static final class GroupDetailHostFragment extends LayoutHostFragment {
        public GroupDetailHostFragment() {
            super(R.layout.fragment_group_detail);
        }
    }

    public static final class MediaViewerHostFragment extends LayoutHostFragment {
        public MediaViewerHostFragment() {
            super(R.layout.fragment_media_viewer);
        }
    }
}

package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/** Resolves the caller and group member names for manage affordances and voter labels. */
public interface PollViewerSource {

    Cancellable load(@NonNull String conversationId, @NonNull Callback callback);

    interface Cancellable {
        void cancel();
    }

    interface Callback {
        void onLoaded(@NonNull List<Member> members, @Nullable String myUserId);
    }

    /** One group member; role is null when the payload omits it. */
    final class Member {
        private final String userId;
        private final String displayName;
        private final boolean admin;

        public Member(@NonNull String userId, @NonNull String displayName, boolean admin) {
            this.userId = Objects.requireNonNull(userId, "userId");
            this.displayName = Objects.requireNonNull(displayName, "displayName");
            this.admin = admin;
        }

        @NonNull public String getUserId() { return userId; }
        @NonNull public String getDisplayName() { return displayName; }
        public boolean isAdmin() { return admin; }
    }
}

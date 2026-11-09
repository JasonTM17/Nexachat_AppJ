package com.nexachat.feature.invite;

import androidx.annotation.Nullable;

/** Request summary returned to group admins and to the requesting member. */
public final class JoinRequestResponse {
    public String id;
    public String conversationId;
    @Nullable public UserSummary user;
    public String status;
    public String createdAt;
    @Nullable public String reviewedAt;

    public JoinRequestResponse() { }

    public static final class UserSummary {
        public String id;
        public String displayName;
        @Nullable public String avatarUrl;

        public UserSummary() { }
    }
}

package com.nexachat.feature.invite;

import androidx.annotation.Nullable;

/** Result of joining directly or submitting an approval request. */
public final class JoinResponse {
    public String status;
    public String conversationId;
    @Nullable public String requestId;

    public JoinResponse() { }
}

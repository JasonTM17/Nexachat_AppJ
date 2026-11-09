package com.nexachat.feature.invite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InviteRequestAdapterTest {
    @Test
    public void contentDiffIncludesDisplayedRequesterProfile() {
        JoinRequestResponse original = request("user-1", "Mai", "https://img/old.png");

        assertTrue(InviteRequestAdapter.areSameContent(original,
                request("user-1", "Mai", "https://img/old.png")));
        assertFalse(InviteRequestAdapter.areSameContent(original,
                request("user-1", "Lan", "https://img/old.png")));
        assertFalse(InviteRequestAdapter.areSameContent(original,
                request("user-1", "Mai", "https://img/new.png")));
    }

    private static JoinRequestResponse request(String userId, String name, String avatar) {
        JoinRequestResponse result = new JoinRequestResponse();
        result.id = "request-1";
        result.status = "PENDING";
        result.createdAt = "2026-11-09T10:00:00Z";
        result.user = new JoinRequestResponse.UserSummary();
        result.user.id = userId;
        result.user.displayName = name;
        result.user.avatarUrl = avatar;
        return result;
    }
}
